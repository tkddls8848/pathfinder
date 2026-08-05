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

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TAGO_BASE = "https://apis.data.go.kr/1613000/"
    private const val DATA_GO_KR_BASE = "https://apis.data.go.kr/"
    private const val WEATHER_BASE = "https://apis.data.go.kr/1360000/"
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
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        // 인증키가 로그에 남지 않도록 마스킹한다.
                        redactQueryParams("serviceKey", "confmKey", "KEY")
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

    @Provides @Singleton @Named("datagokr")
    fun dataGoKrRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit = retrofit(DATA_GO_KR_BASE, client, moshi)

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
    fun hospitalApi(@Named("datagokr") r: Retrofit): HospitalApi = r.create(HospitalApi::class.java)

    @Provides @Singleton
    fun weatherApi(@Named("weather") r: Retrofit): kr.eodiga.wayfinder.data.remote.api.WeatherApi =
        r.create(kr.eodiga.wayfinder.data.remote.api.WeatherApi::class.java)

    @Provides @Singleton
    fun jusoApi(@Named("juso") r: Retrofit): JusoApi = r.create(JusoApi::class.java)
}
