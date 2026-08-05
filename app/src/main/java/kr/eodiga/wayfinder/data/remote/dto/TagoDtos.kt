package kr.eodiga.wayfinder.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * TAGO 필드명은 전부 소문자다 (nodeid, gpslati …). 카멜케이스로 바꾸지 말 것.
 * 데이터셋: 국토교통부_(TAGO)_버스정류소정보 / 버스도착정보 / 버스노선정보 / 버스위치정보
 */

/** getCrdntPrxmtSttnList — 좌표 기반 근접 정류소 목록. */
@JsonClass(generateAdapter = true)
data class NearbyStopDto(
    @Json(name = "citycode") val cityCode: Int? = null,
    @Json(name = "nodeid") val nodeId: String? = null,
    @Json(name = "nodenm") val nodeName: String? = null,
    @Json(name = "nodeno") @LenientString val nodeNo: String? = null,
    @Json(name = "gpslati") val lat: Double? = null,
    @Json(name = "gpslong") val lng: Double? = null,
)

/** getSttnAcctoArvlPrearngeInfoList — 정류소별 실시간 도착 예정. */
@JsonClass(generateAdapter = true)
data class BusArrivalDto(
    @Json(name = "nodeid") val nodeId: String? = null,
    @Json(name = "nodenm") val nodeName: String? = null,
    @Json(name = "routeid") val routeId: String? = null,
    @Json(name = "routeno") @LenientString val routeNo: String? = null,
    @Json(name = "routetp") val routeType: String? = null,
    @Json(name = "arrtime") val arrivalSeconds: Int? = null,
    @Json(name = "arrprevstationcnt") val prevStationCount: Int? = null,
    @Json(name = "vehicletp") val vehicleType: String? = null,
)

/** getSttnThrghRouteList — 정류소를 경유하는 노선 목록. 환승 후보를 만드는 핵심 데이터. */
@JsonClass(generateAdapter = true)
data class StopRouteDto(
    @Json(name = "routeid") val routeId: String? = null,
    @Json(name = "routeno") @LenientString val routeNo: String? = null,
    @Json(name = "routetp") val routeType: String? = null,
    @Json(name = "startnodenm") val startNodeName: String? = null,
    @Json(name = "endnodenm") val endNodeName: String? = null,
    @Json(name = "startvehicletime") @LenientString val firstBusTime: String? = null,
    @Json(name = "endvehicletime") @LenientString val lastBusTime: String? = null,
)

/** getRouteAcctoSttnList — 노선별 경유 정류소 목록(순번 포함). */
@JsonClass(generateAdapter = true)
data class RouteStopDto(
    @Json(name = "routeid") val routeId: String? = null,
    @Json(name = "nodeid") val nodeId: String? = null,
    @Json(name = "nodenm") val nodeName: String? = null,
    @Json(name = "nodeno") @LenientString val nodeNo: String? = null,
    @Json(name = "nodeord") val nodeOrder: Int? = null,
    @Json(name = "gpslati") val lat: Double? = null,
    @Json(name = "gpslong") val lng: Double? = null,
    @Json(name = "updowncd") val upDownCode: Int? = null,
)

/** getRouteAcctoBusLcList — 노선의 실시간 차량 위치. 하차 알림 정확도의 근거. */
@JsonClass(generateAdapter = true)
data class BusLocationDto(
    @Json(name = "vehicleno") @LenientString val vehicleNo: String? = null,
    @Json(name = "nodeid") val nodeId: String? = null,
    @Json(name = "nodenm") val nodeName: String? = null,
    @Json(name = "nodeord") val nodeOrder: Int? = null,
    @Json(name = "gpslati") val lat: Double? = null,
    @Json(name = "gpslong") val lng: Double? = null,
    @Json(name = "routetp") val routeType: String? = null,
)
