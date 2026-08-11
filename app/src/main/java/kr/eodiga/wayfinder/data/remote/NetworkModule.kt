package kr.eodiga.wayfinder.data.remote

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kr.eodiga.wayfinder.BuildConfig
import kr.eodiga.wayfinder.data.remote.api.HospitalApi
import kr.eodiga.wayfinder.data.remote.api.JusoApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusArrivalApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusLocationApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusRouteApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusStopApi
import kr.eodiga.wayfinder.data.remote.dto.LenientStringAdapter
import kr.eodiga.wayfinder.data.remote.dto.TagoItemsAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * BASIC 레벨은 요청 URL 을 통째로 찍는다. 공공데이터포털은 인증키를 쿼리
 * 파라미터로 받으므로 그대로 두면 인증키가 로그에 남는다.
 *
 * OkHttp 4 의 [HttpLoggingInterceptor] 에는 쿼리 파라미터 마스킹이 없다
 * (`redactQueryParams` 는 OkHttp 5 부터다). 헤더용 `redactHeader` 로는
 * 가릴 수 없으므로 로거 단에서 값만 지운다.
 */
private val SECRET_QUERY_PARAM = Regex(
    """([?&](?:serviceKey|confmKey|key)=)[^&\s]*""",
    RegexOption.IGNORE_CASE,
)

internal fun redactSecrets(message: String): String =
    SECRET_QUERY_PARAM.replace(message) { "${it.groupValues[1]}<redacted>" }

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /*
     * 공공데이터포털은 호스트가 같고 **기관코드로 서비스를 가른다.**
     * 그래서 베이스 URL 에서 기관코드가 빠지면 인증 오류가 아니라
     * `NO_OPENAPI_SERVICE_ERROR`("해당 오픈API 서비스가 없거나 폐기됨") 가 돌아온다.
     * 키 문제로 착각하기 쉬우니 상수 이름에 어느 서비스인지 남긴다.
     */
    private const val TAGO_BASE = "https://apis.data.go.kr/1613000/"        // 국토교통부 TAGO
    private const val HOSPITAL_BASE = "https://apis.data.go.kr/B552657/"    // 국립중앙의료원
    private const val WEATHER_BASE = "https://apis.data.go.kr/1360000/"     // 기상청
    private const val JUSO_BASE = "https://business.juso.go.kr/addrlink/"

    /** 모든 DTO 가 @JsonClass(generateAdapter = true) 이므로 리플렉션 어댑터는 쓰지 않는다. */
    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder()
        .add(TagoItemsAdapterFactory())
        .add(LenientStringAdapter)
        .build()

    @Provides
    @Singleton
    fun okHttp(
        keyGuard: MissingKeyGuardInterceptor,
        publicData: PublicDataInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        // 어르신은 대기 화면에서 기다리지 못한다. 짧게 끊고 재시도하는 편이 낫다.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(keyGuard)
        .addInterceptor(publicData)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor { message ->
                        HttpLoggingInterceptor.Logger.DEFAULT.log(redactSecrets(message))
                    }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    },
                )
            }
        }
        .build()

    private fun retrofit(base: String, client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
            .build()

    @Provides @Singleton @Named("tago")
    fun tagoRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit = retrofit(TAGO_BASE, client, moshi)

    @Provides @Singleton @Named("hospital")
    fun hospitalRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit = retrofit(HOSPITAL_BASE, client, moshi)

    @Provides @Singleton @Named("weather")
    fun weatherRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit = retrofit(WEATHER_BASE, client, moshi)

    /** 주소검색 API 는 인증 방식이 달라 공통 인터셉터를 태우지 않는다. */
    @Provides @Singleton @Named("juso")
    fun jusoRetrofit(moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(JUSO_BASE)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
        .build()

    @Provides @Singleton
    fun busStopApi(@Named("tago") r: Retrofit): TagoBusStopApi = r.create(TagoBusStopApi::class.java)

    @Provides @Singleton
    fun busArrivalApi(@Named("tago") r: Retrofit): TagoBusArrivalApi = r.create(TagoBusArrivalApi::class.java)

    @Provides @Singleton
    fun busRouteApi(@Named("tago") r: Retrofit): TagoBusRouteApi = r.create(TagoBusRouteApi::class.java)

    @Provides @Singleton
    fun busLocationApi(@Named("tago") r: Retrofit): TagoBusLocationApi = r.create(TagoBusLocationApi::class.java)

    @Provides @Singleton
    fun hospitalApi(@Named("hospital") r: Retrofit): HospitalApi = r.create(HospitalApi::class.java)

    @Provides @Singleton
    fun weatherApi(@Named("weather") r: Retrofit): kr.eodiga.wayfinder.data.remote.api.WeatherApi =
        r.create(kr.eodiga.wayfinder.data.remote.api.WeatherApi::class.java)

    @Provides @Singleton
    fun jusoApi(@Named("juso") r: Retrofit): JusoApi = r.create(JusoApi::class.java)
}
