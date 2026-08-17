package kr.eodiga.wayfinder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.core.outcomeOf
import kr.eodiga.wayfinder.core.retrying
import kr.eodiga.wayfinder.data.local.GuardianDao
import kr.eodiga.wayfinder.data.local.GuardianEntity
import kr.eodiga.wayfinder.data.local.SavedPlaceDao
import kr.eodiga.wayfinder.data.local.SavedPlaceEntity
import kr.eodiga.wayfinder.data.remote.ServiceKeyProvider
import kr.eodiga.wayfinder.data.remote.api.HospitalApi
import kr.eodiga.wayfinder.data.remote.api.JusoApi
import kr.eodiga.wayfinder.data.remote.dto.itemsOrThrow
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.PlaceKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 목적지 검색과 저장.
 *
 * 검색 소스는 세 가지다.
 *  1. 저장된 장소 (집/병원/아들집) — 오프라인에서도 동작해야 하므로 최우선.
 *  2. 국립중앙의료원 전국 병·의원 찾기 — "병원" 이라고 말했을 때.
 *  3. 도로명주소 검색 API — 그 외 일반 주소.
 */
@Singleton
class PlaceRepository @Inject constructor(
    private val savedPlaceDao: SavedPlaceDao,
    private val guardianDao: GuardianDao,
    private val hospitalApi: HospitalApi,
    private val jusoApi: JusoApi,
    private val keys: ServiceKeyProvider,
) {

    fun pinnedPlaces(): Flow<List<Place>> =
        savedPlaceDao.pinned().map { list -> list.map { it.toDomain() } }

    fun recentPlaces(): Flow<List<Place>> =
        savedPlaceDao.recent().map { list -> list.map { it.toDomain() } }

    fun guardians(): Flow<List<GuardianEntity>> = guardianDao.all()

    suspend fun primaryGuardian(): GuardianEntity? = guardianDao.primary()

    suspend fun saveGuardian(guardian: GuardianEntity) = guardianDao.upsert(guardian)

    /** 저장된 값 기준으로 다음 우선순위를 매긴다. 화면 상태를 세지 않는다. */
    suspend fun nextGuardianPriority(): Int = guardianDao.nextPriority()

    suspend fun deleteGuardian(id: String) = guardianDao.delete(id)

    suspend fun savePlace(place: Place, pinnedOrder: Int? = null) {
        val existing = savedPlaceDao.byId(place.id)
        savedPlaceDao.upsert(
            SavedPlaceEntity.from(
                place = place,
                pinnedOrder = pinnedOrder ?: existing?.pinnedOrder,
                visitCount = existing?.visitCount ?: 0,
                lastVisitedAt = existing?.lastVisitedAt ?: 0L,
            ),
        )
    }

    suspend fun deletePlace(id: String) = savedPlaceDao.delete(id)

    /** 안내를 완료했을 때 호출. 자주 가는 곳이 자동으로 위로 올라온다. */
    suspend fun recordVisit(place: Place) {
        if (savedPlaceDao.byId(place.id) == null) savePlace(place)
        savedPlaceDao.markVisited(place.id, System.currentTimeMillis())
    }

    /**
     * 병원 검색. 주소 기반 API 라 시/도 · 시/군/구를 넘겨야 결과가 나온다.
     * 좌표가 비어 있는 레코드가 적지 않아 걸러낸다.
     */
    suspend fun searchHospitals(sido: String, sigungu: String?, keyword: String?): Outcome<List<Place>> =
        outcomeOf {
            retrying {
                hospitalApi.searchHospitals(sido = sido, sigungu = sigungu, name = keyword)
                    .itemsOrThrow()
                    .mapNotNull { dto ->
                        val lat = dto.lat ?: return@mapNotNull null
                        val lng = dto.lng ?: return@mapNotNull null
                        Place(
                            // hpid 가 없을 때 이름만으로 키를 만들면 동명 병원이
                            // 같은 id 를 갖는다. 목록의 key 가 겹쳐 화면이 죽는다.
                            id = dto.id ?: "hpid:${dto.name}@${dto.address}",
                            name = dto.name.orEmpty(),
                            address = dto.address,
                            location = LatLng(lat, lng),
                            kind = PlaceKind.HOSPITAL,
                        )
                    }
                    .filter { it.location.isValid() }
            }
        }

    /**
     * 도로명주소 검색.
     *
     * 주의: 이 API 는 좌표를 바로 주지 않는다. 검색으로 주소 식별자를 얻은 뒤
     * 좌표변환 API 를 한 번 더 호출해야 하고, 그 좌표도 WGS84 가 아니라
     * GRS80 중부원점(EPSG:5179)이다. 변환은 [Epsg5179] 가 담당한다.
     */
    suspend fun searchAddress(keyword: String): Outcome<List<Place>> {
        // 승인키가 없으면 "결과 없음" 이 아니라 설정 문제다. 빈 목록으로 돌려주면
        // 화면에 "그런 곳을 찾지 못했습니다" 가 떠서 원인을 영영 알 수 없다.
        if (keys.jusoKey.isBlank()) return Outcome.Failure(FailureReason.NotConfigured)
        return searchAddressOrThrow(keyword)
    }

    private suspend fun searchAddressOrThrow(keyword: String): Outcome<List<Place>> = outcomeOf {

        val found = jusoApi.searchAddress(
            url = "addrLinkApi.do",
            key = keys.jusoKey,
            page = 1,
            countPerPage = 10,
            keyword = keyword,
        )
        val list = found.results?.juso.orEmpty()

        list.mapNotNull { juso ->
            val coord = runCatching {
                jusoApi.addressToCoord(
                    url = "addrCoordApi.do",
                    key = keys.jusoKey,
                    admCd = juso.adminCode.orEmpty(),
                    rnMgtSn = juso.roadCode.orEmpty(),
                    udrtYn = juso.underground.orEmpty(),
                    buldMnnm = juso.buildingMainNo.orEmpty(),
                    buldSlno = juso.buildingSubNo.orEmpty(),
                )
            }.getOrNull()?.results?.juso?.firstOrNull() ?: return@mapNotNull null

            val x = coord.x?.toDoubleOrNull() ?: return@mapNotNull null
            val y = coord.y?.toDoubleOrNull() ?: return@mapNotNull null
            val wgs = Epsg5179.toWgs84(x, y)

            Place(
                // id 를 비워 두면 Place 의 기본값인 UUID 가 매번 새로 생긴다.
                // 같은 주소를 두 번 저장하면 별개의 장소로 쌓이고, 자주 가는 곳이
                // 위로 올라오지도 않는다. 주소 자체로 안정된 키를 만든다.
                id = "juso:${juso.adminCode.orEmpty()}:${juso.roadCode.orEmpty()}:" +
                    "${juso.buildingMainNo.orEmpty()}-${juso.buildingSubNo.orEmpty()}",
                name = juso.buildingName?.takeIf { it.isNotBlank() } ?: juso.roadAddress.orEmpty(),
                address = juso.roadAddress,
                location = wgs,
                kind = PlaceKind.OTHER,
            )
        }.distinctBy { it.id }.filter { it.location.isValid() }
    }
}
