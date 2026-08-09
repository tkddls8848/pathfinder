package kr.eodiga.wayfinder.data.remote.api

import kr.eodiga.wayfinder.data.remote.dto.BusArrivalDto
import kr.eodiga.wayfinder.data.remote.dto.BusLocationDto
import kr.eodiga.wayfinder.data.remote.dto.NearbyStopDto
import kr.eodiga.wayfinder.data.remote.dto.RouteStopDto
import kr.eodiga.wayfinder.data.remote.dto.StopRouteDto
import kr.eodiga.wayfinder.data.remote.dto.TagoEnvelope
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 국토교통부 (TAGO) 오픈API.
 *
 * serviceKey 와 `_type=json` 은 [kr.eodiga.wayfinder.data.remote.PublicDataInterceptor] 가
 * 모든 요청에 자동으로 붙이므로 여기서 선언하지 않는다.
 *
 * base url: https://apis.data.go.kr/1613000/
 */
interface TagoBusStopApi {

    /**
     * 좌표 기반 근접 정류소 목록.
     * 데이터셋: 국토교통부_(TAGO)_버스정류소정보 (data.go.kr/data/15098534)
     */
    @GET("BusSttnInfoInqireService/getCrdntPrxmtSttnList")
    suspend fun nearbyStops(
        @Query("gpsLati") lat: Double,
        @Query("gpsLong") lng: Double,
        @Query("numOfRows") numOfRows: Int = 30,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<NearbyStopDto>

    /** 해당 정류소를 지나는 노선 목록. */
    @GET("BusSttnInfoInqireService/getSttnThrghRouteList")
    suspend fun routesThroughStop(
        @Query("cityCode") cityCode: Int,
        @Query("nodeid") nodeId: String,
        @Query("numOfRows") numOfRows: Int = 60,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<StopRouteDto>
}

interface TagoBusArrivalApi {

    /** 특정 노선 한 대만 조회. 대기 화면에서 폴링 비용을 줄이는 데 쓴다. */
    @GET("ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList")
    suspend fun arrivalOfRoute(
        @Query("cityCode") cityCode: Int,
        @Query("nodeId") nodeId: String,
        @Query("routeId") routeId: String,
        @Query("numOfRows") numOfRows: Int = 5,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<BusArrivalDto>
}

interface TagoBusRouteApi {

    /**
     * 노선별 경유 정류소 목록(순번 포함).
     * 데이터셋: 국토교통부_(TAGO)_버스노선정보 (data.go.kr/data/15098529)
     *
     * 오퍼레이션 이름에 `Thrgh`(경유)가 들어간다. `getRouteAcctoSttnList` 로 부르면
     * `NO_OPENAPI_SERVICE_ERROR` 가 돌아온다 — 인증이나 파라미터 문제로 보이는 오류라
     * 원인을 찾기 어렵다. 실제로 이 오타 때문에 경로 탐색이 통째로 실패하고 있었다.
     */
    @GET("BusRouteInfoInqireService/getRouteAcctoThrghSttnList")
    suspend fun stopsOnRoute(
        @Query("cityCode") cityCode: Int,
        @Query("routeId") routeId: String,
        @Query("numOfRows") numOfRows: Int = 200,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<RouteStopDto>
}

interface TagoBusLocationApi {

    /**
     * 노선의 실시간 차량 위치.
     * 데이터셋: 국토교통부_(TAGO)_버스위치정보 (data.go.kr/data/15098533)
     *
     * 승차 후 "몇 정거장 남았는지" 를 도착예정시간이 아니라 실제 차량 순번으로
     * 계산하기 위해 쓴다. 하차 알림이 빗나가지 않게 하는 가장 중요한 데이터.
     */
    @GET("BusLcInfoInqireService/getRouteAcctoBusLcList")
    suspend fun busesOnRoute(
        @Query("cityCode") cityCode: Int,
        @Query("routeId") routeId: String,
        @Query("numOfRows") numOfRows: Int = 60,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<BusLocationDto>
}
