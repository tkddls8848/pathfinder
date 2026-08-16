package kr.eodiga.wayfinder.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlin.math.abs
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.core.outcomeOf
import kr.eodiga.wayfinder.core.retrying
import kr.eodiga.wayfinder.data.remote.api.TagoBusArrivalApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusLocationApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusRouteApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusStopApi
import kr.eodiga.wayfinder.data.remote.dto.itemsOrThrow
import kr.eodiga.wayfinder.domain.model.BusArrival
import kr.eodiga.wayfinder.domain.model.BusKind
import kr.eodiga.wayfinder.domain.model.BusRoute
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.Geo
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.RouteStop
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 버스 관련 공공데이터 접근을 한 곳에 모은다.
 *
 * 노선별 경유 정류소 목록은 하루 단위로 거의 바뀌지 않으므로 메모리에 캐시한다.
 * 경로 탐색 한 번에 이 API 를 수십 번 호출하게 되는데, 개발계정 일일 트래픽이
 * 10,000 건이라 캐시 없이는 실사용에서 금방 한도를 넘긴다.
 */
@Singleton
class TransitRepository @Inject constructor(
    private val stopApi: TagoBusStopApi,
    private val arrivalApi: TagoBusArrivalApi,
    private val routeApi: TagoBusRouteApi,
    private val locationApi: TagoBusLocationApi,
) {

    private companion object {
        /** 승차 직후 위치 API 가 한두 정류장 늦게 갱신되는 경우만 허용한다. */
        const val BOARDING_MATCH_TOLERANCE_STOPS = 2
    }

    private val routeStopCache = mutableMapOf<String, List<RouteStop>>()
    private val stopRouteCache = mutableMapOf<String, List<BusRoute>>()

    /** 현재 위치 주변 정류소. 가까운 순으로 정렬해서 돌려준다. */
    suspend fun nearbyStops(at: LatLng, limit: Int = 12): Outcome<List<BusStop>> = outcomeOf {
        retrying {
            stopApi.nearbyStops(lat = at.lat, lng = at.lng, numOfRows = 40)
                .itemsOrThrow()
                .mapNotNull { dto ->
                    val lat = dto.lat ?: return@mapNotNull null
                    val lng = dto.lng ?: return@mapNotNull null
                    val id = dto.nodeId ?: return@mapNotNull null
                    val city = dto.cityCode ?: return@mapNotNull null
                    BusStop(
                        cityCode = city,
                        nodeId = id,
                        name = dto.nodeName.orEmpty(),
                        location = LatLng(lat, lng),
                    )
                }
                .sortedBy { Geo.distanceMeters(at, it.location) }
                .take(limit)
        }
    }

    /** 정류소를 경유하는 노선 목록. */
    suspend fun routesThroughStop(stop: BusStop): Outcome<List<BusRoute>> = outcomeOf {
        stopRouteCache[stop.nodeId]?.let { return@outcomeOf it }
        val routes = retrying {
            stopApi.routesThroughStop(cityCode = stop.cityCode, nodeId = stop.nodeId)
                .itemsOrThrow()
                .mapNotNull { dto ->
                    val id = dto.routeId ?: return@mapNotNull null
                    BusRoute(
                        cityCode = stop.cityCode,
                        routeId = id,
                        routeNo = dto.routeNo.orEmpty(),
                        kind = BusKind.from(dto.routeType),
                    )
                }
        }
        stopRouteCache[stop.nodeId] = routes
        routes
    }

    /** 노선의 경유 정류소 목록(순번 오름차순). */
    suspend fun stopsOnRoute(route: BusRoute): Outcome<List<RouteStop>> = outcomeOf {
        routeStopCache[route.routeId]?.let { return@outcomeOf it }
        val stops = retrying {
            routeApi.stopsOnRoute(cityCode = route.cityCode, routeId = route.routeId, numOfRows = 300)
                .itemsOrThrow()
                .mapNotNull { dto ->
                    RouteStop(
                        order = dto.nodeOrder ?: return@mapNotNull null,
                        nodeId = dto.nodeId ?: return@mapNotNull null,
                        name = dto.nodeName.orEmpty(),
                        location = LatLng(dto.lat ?: 0.0, dto.lng ?: 0.0),
                    )
                }
                .sortedBy { it.order }
        }
        routeStopCache[route.routeId] = stops
        stops
    }

    /** 특정 노선 하나의 도착 예정. 대기 화면 폴링용. */
    suspend fun arrivalOf(stop: BusStop, routeId: String): Outcome<BusArrival?> = outcomeOf {
        retrying {
            arrivalApi.arrivalOfRoute(cityCode = stop.cityCode, nodeId = stop.nodeId, routeId = routeId)
                .itemsOrThrow()
                .map { it.toDomain() }
                .minByOrNull { it.arrivalSeconds }
        }
    }

    /**
     * 대기 화면용 도착정보 스트림.
     *
     * 폴링 간격을 도착 임박도에 따라 좁힌다. 항상 10초로 돌리면 어르신이
     * 정류장에서 20분 기다리는 동안에만 120번을 호출해 트래픽을 태운다.
     */
    fun arrivalStream(stop: BusStop, routeId: String): Flow<Outcome<BusArrival?>> = flow {
        while (true) {
            val result = arrivalOf(stop, routeId)
            emit(result)
            val seconds = result.getOrNull()?.arrivalSeconds
            val interval = when {
                seconds == null -> 30_000L
                seconds <= 90 -> 8_000L    // 곧 도착: 촘촘히
                seconds <= 300 -> 15_000L  // 5분 이내
                else -> 30_000L
            }
            delay(interval)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 승차 후 남은 정거장 수를 실시간 차량 위치로 계산한다.
     *
     * @param boardOrder 승차 정류소 순번. 차량번호를 아직 모를 때 승차 지점 근처의
     *                   차량만 최초 후보로 삼는 안전 경계다.
     * @param vehicleNo 승차한 차량 번호를 알면 그 차량만 추적한다. 해당 차량이
     *                  응답에서 사라져도 다른 차량으로 갈아타지 않는다.
     */
    suspend fun stopsRemaining(
        route: BusRoute,
        boardOrder: Int,
        alightOrder: Int,
        vehicleNo: String?,
    ): Outcome<BusProgress?> = outcomeOf {
        val buses = retrying {
            locationApi.busesOnRoute(cityCode = route.cityCode, routeId = route.routeId)
                .itemsOrThrow()
        }
        val candidates = buses.mapNotNull { bus ->
            val order = bus.nodeOrder ?: return@mapNotNull null
            val number = bus.vehicleNo ?: return@mapNotNull null
            if (order > alightOrder) return@mapNotNull null
            BusProgress(
                vehicleNo = number,
                currentOrder = order,
                stopsRemaining = alightOrder - order,
            )
        }
        val chosen = when {
            vehicleNo != null -> candidates.firstOrNull { it.vehicleNo == vehicleNo }
            else -> candidates
                .filter { abs(it.currentOrder - boardOrder) <= BOARDING_MATCH_TOLERANCE_STOPS }
                // 같은 노선 차량이 승차 지점 근처에 둘 이상이면 어느 차에 탔는지
                // 데이터만으로 확정할 수 없다. 임의 선택은 잘못된 하차 알림보다 위험하다.
                .singleOrNull()
        } ?: return@outcomeOf null
        chosen
    }

    /** 위치 추적 스트림. 포그라운드 서비스에서 소비한다. */
    fun progressStream(
        route: BusRoute,
        boardOrder: Int,
        alightOrder: Int,
        vehicleNo: String?,
        intervalMs: Long = 15_000,
    ): Flow<Outcome<BusProgress?>> = flow {
        var trackedVehicleNo = vehicleNo
        var mayDiscoverVehicle = vehicleNo == null
        while (true) {
            val result = if (trackedVehicleNo == null && !mayDiscoverVehicle) {
                // 승차 직후의 첫 정상 응답에서 차량을 특정하지 못했다면 이후 도착한
                // 다른 차량을 탑승 차량으로 오인하지 않는다.
                Outcome.Success(null)
            } else {
                stopsRemaining(route, boardOrder, alightOrder, trackedVehicleNo)
            }
            if (result is Outcome.Success && trackedVehicleNo == null) {
                mayDiscoverVehicle = false
                result.value?.vehicleNo?.let { trackedVehicleNo = it }
            }
            emit(result)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun kr.eodiga.wayfinder.data.remote.dto.BusArrivalDto.toDomain() = BusArrival(
        arrivalSeconds = arrivalSeconds ?: 0,
        vehicleType = vehicleType,
    )
}

/** 승차 중인 버스의 진행 상황. */
data class BusProgress(
    val vehicleNo: String,
    val currentOrder: Int,
    val stopsRemaining: Int,
)
