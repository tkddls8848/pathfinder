package kr.eodiga.wayfinder.domain.model

/**
 * 버스 정류소. TAGO `BusSttnInfoInqireService` 의 정류소 레코드에 대응한다.
 *
 * @param cityCode TAGO 도시코드. 도착정보/노선정보 조회에 반드시 함께 보내야 한다.
 * @param nodeId   정류소 ID (예: DJB8001793). 도착정보 조회 키.
 * @param nodeNo   정류소 번호 (예: 42381). 어르신이 정류장 기둥에서 눈으로 확인하는 번호.
 */
data class BusStop(
    val cityCode: Int,
    val nodeId: String,
    val nodeNo: String?,
    val name: String,
    val location: LatLng,
    val direction: String? = null,
)

/** 노선 종류. TAGO routeTp 문자열을 정규화한 것. */
enum class BusKind(val label: String, val colorName: String) {
    TOWN("마을버스", "초록색"),
    CITY("일반버스", "파란색"),
    SEATED("좌석버스", "빨간색"),
    EXPRESS("급행버스", "빨간색"),
    TRUNK("간선버스", "파란색"),
    BRANCH("지선버스", "초록색"),
    AIRPORT("공항버스", "하늘색"),
    UNKNOWN("버스", "");

    companion object {
        fun from(routeTp: String?): BusKind = when {
            routeTp == null -> UNKNOWN
            routeTp.contains("마을") -> TOWN
            routeTp.contains("급행") -> EXPRESS
            routeTp.contains("간선") -> TRUNK
            routeTp.contains("지선") -> BRANCH
            routeTp.contains("좌석") -> SEATED
            routeTp.contains("공항") -> AIRPORT
            routeTp.contains("일반") -> CITY
            else -> UNKNOWN
        }
    }
}

/** 버스 노선. */
data class BusRoute(
    val cityCode: Int,
    val routeId: String,
    val routeNo: String,
    val kind: BusKind = BusKind.UNKNOWN,
    val startStopName: String? = null,
    val endStopName: String? = null,
)

/**
 * 실시간 도착 예정 정보.
 * TAGO `ArvlInfoInqireService.getSttnAcctoArvlPrearngeInfoList` 응답 1건.
 *
 * @param arrivalSeconds arrtime(초). 0 이하이면 "곧 도착".
 * @param stopsAway      arrprevstationcnt. 남은 정거장 수.
 */
data class BusArrival(
    val routeId: String,
    val routeNo: String,
    val kind: BusKind,
    val arrivalSeconds: Int,
    val stopsAway: Int,
    val vehicleType: String? = null,
) {
    val arrivalMinutes: Int get() = (arrivalSeconds / 60).coerceAtLeast(0)

    /** 저상버스 여부. 휠체어·보행보조기 이용 어르신에게 중요한 정보다. */
    val isLowFloor: Boolean get() = vehicleType?.contains("저상") == true

    /** 1분 이내이면 "곧 옵니다, 손을 드세요" 로 화면을 전환한다. */
    val isImminent: Boolean get() = arrivalSeconds <= 60
}

/** 노선의 n번째 경유 정류소. 승·하차 순서 검증과 남은 정거장 계산에 쓴다. */
data class RouteStop(
    val order: Int,
    val nodeId: String,
    val nodeNo: String?,
    val name: String,
    val location: LatLng,
)
