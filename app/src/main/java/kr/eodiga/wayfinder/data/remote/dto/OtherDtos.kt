package kr.eodiga.wayfinder.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/* ── 국립중앙의료원_전국 병·의원 찾기 (B552657/HsptlAsembySearchService) ── */

@JsonClass(generateAdapter = true)
data class HospitalDto(
    @Json(name = "dutyName") val name: String? = null,
    @Json(name = "dutyAddr") val address: String? = null,
    @Json(name = "dutyTel1") val phone: String? = null,
    @Json(name = "dutyDivNam") val category: String? = null,
    @Json(name = "wgs84Lat") val lat: Double? = null,
    @Json(name = "wgs84Lon") val lng: Double? = null,
    @Json(name = "hpid") val id: String? = null,
)

/* ── 기상청_단기예보 조회서비스 (1360000/VilageFcstInfoService_2.0) ── */

@JsonClass(generateAdapter = true)
data class WeatherItemDto(
    @Json(name = "category") val category: String? = null,
    @Json(name = "obsrValue") @LenientString val observedValue: String? = null,
    @Json(name = "fcstValue") @LenientString val forecastValue: String? = null,
    @Json(name = "baseDate") val baseDate: String? = null,
    @Json(name = "baseTime") val baseTime: String? = null,
)

/* ── 도로명주소 검색 API (business.juso.go.kr) ── 봉투 구조가 TAGO 와 다르다. */

@JsonClass(generateAdapter = true)
data class JusoEnvelope(
    @Json(name = "results") val results: JusoResults? = null,
)

@JsonClass(generateAdapter = true)
data class JusoResults(
    @Json(name = "common") val common: JusoCommon? = null,
    @Json(name = "juso") val juso: List<JusoDto>? = null,
)

@JsonClass(generateAdapter = true)
data class JusoCommon(
    @Json(name = "errorCode") val errorCode: String? = null,
    @Json(name = "errorMessage") val errorMessage: String? = null,
    @Json(name = "totalCount") @LenientString val totalCount: String? = null,
)

@JsonClass(generateAdapter = true)
data class JusoDto(
    @Json(name = "roadAddr") val roadAddress: String? = null,
    @Json(name = "jibunAddr") val jibunAddress: String? = null,
    @Json(name = "bdNm") val buildingName: String? = null,
    @Json(name = "admCd") val adminCode: String? = null,
    @Json(name = "rnMgtSn") val roadCode: String? = null,
    @Json(name = "udrtYn") val underground: String? = null,
    @Json(name = "buldMnnm") @LenientString val buildingMainNo: String? = null,
    @Json(name = "buldSlno") @LenientString val buildingSubNo: String? = null,
)

/** 좌표 변환 API(addrCoordApi) 응답. 도로명주소를 WGS84 가 아닌 GRS80(EPSG:5179)로 준다. */
@JsonClass(generateAdapter = true)
data class JusoCoordEnvelope(
    @Json(name = "results") val results: JusoCoordResults? = null,
)

@JsonClass(generateAdapter = true)
data class JusoCoordResults(
    @Json(name = "common") val common: JusoCommon? = null,
    @Json(name = "juso") val juso: List<JusoCoordDto>? = null,
)

@JsonClass(generateAdapter = true)
data class JusoCoordDto(
    @Json(name = "entX") @LenientString val x: String? = null,
    @Json(name = "entY") @LenientString val y: String? = null,
    @Json(name = "roadAddr") val roadAddress: String? = null,
    @Json(name = "bdNm") val buildingName: String? = null,
)
