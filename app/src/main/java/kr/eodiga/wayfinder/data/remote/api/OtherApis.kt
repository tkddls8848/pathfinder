package kr.eodiga.wayfinder.data.remote.api

import kr.eodiga.wayfinder.data.remote.dto.HospitalDto
import kr.eodiga.wayfinder.data.remote.dto.JusoCoordEnvelope
import kr.eodiga.wayfinder.data.remote.dto.JusoEnvelope
import kr.eodiga.wayfinder.data.remote.dto.TagoEnvelope
import kr.eodiga.wayfinder.data.remote.dto.WeatherItemDto
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 국립중앙의료원_전국 병·의원 찾기 서비스 (data.go.kr/data/15000736)
 * base url: https://apis.data.go.kr/B552657/
 *
 * 어르신 목적지 1순위가 병원이라 별도 검색 경로를 둔다.
 * 주소 기반 검색이라 시/도, 시/군/구를 함께 넘긴다.
 */
interface HospitalApi {

    @GET("HsptlAsembySearchService/getHsptlMdcncListInfoInqire")
    suspend fun searchHospitals(
        @Query("Q0") sido: String,
        @Query("Q1") sigungu: String? = null,
        @Query("QN") name: String? = null,
        @Query("numOfRows") numOfRows: Int = 20,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<HospitalDto>
}

/**
 * 기상청_단기예보 조회서비스 (data.go.kr/data/15084084)
 * base url: https://apis.data.go.kr/1360000/
 *
 * 폭염·한파·강수는 어르신 외출에서 낙상·온열질환과 직결된다.
 * 출발 전 경고를 띄우기 위해 초단기실황만 가볍게 조회한다.
 */
interface WeatherApi {

    @GET("VilageFcstInfoService_2.0/getUltraSrtNcst")
    suspend fun ultraShortNowcast(
        @Query("base_date") baseDate: String,
        @Query("base_time") baseTime: String,
        @Query("nx") nx: Int,
        @Query("ny") ny: Int,
        @Query("numOfRows") numOfRows: Int = 20,
        @Query("pageNo") pageNo: Int = 1,
    ): TagoEnvelope<WeatherItemDto>
}

/**
 * 도로명주소 검색 API (business.juso.go.kr).
 *
 * 공공데이터포털이 아니라 주소기반산업지원서비스에서 별도 승인키를 받는다.
 * 응답 봉투 구조가 완전히 달라 별도 Retrofit 인스턴스를 쓴다.
 */
interface JusoApi {

    @GET
    suspend fun searchAddress(
        @Url url: String,
        @Query("confmKey") key: String,
        @Query("currentPage") page: Int,
        @Query("countPerPage") countPerPage: Int,
        @Query("keyword") keyword: String,
        @Query("resultType") resultType: String = "json",
    ): JusoEnvelope

    @GET
    suspend fun addressToCoord(
        @Url url: String,
        @Query("confmKey") key: String,
        @Query("admCd") admCd: String,
        @Query("rnMgtSn") rnMgtSn: String,
        @Query("udrtYn") udrtYn: String,
        @Query("buldMnnm") buldMnnm: String,
        @Query("buldSlno") buldSlno: String,
        @Query("resultType") resultType: String = "json",
    ): JusoCoordEnvelope
}
