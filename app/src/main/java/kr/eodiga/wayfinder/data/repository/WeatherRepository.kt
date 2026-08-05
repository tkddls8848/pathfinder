package kr.eodiga.wayfinder.data.repository

import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.core.outcomeOf
import kr.eodiga.wayfinder.core.retrying
import kr.eodiga.wayfinder.data.remote.api.WeatherApi
import kr.eodiga.wayfinder.data.remote.dto.itemsOrThrow
import kr.eodiga.wayfinder.domain.model.LatLng
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 출발 전 날씨 경고.
 *
 * 어르신에게 날씨는 편의 정보가 아니라 안전 정보다.
 * 빙판 낙상, 폭염 온열질환, 한파 저체온증은 모두 사망까지 가는 위험이고,
 * 실제로 고령층 계절 사망률과 직결된다. 그래서 "비 옴" 이 아니라
 * **무엇을 챙기고 무엇을 조심해야 하는지**를 말해준다.
 */
@Singleton
class WeatherRepository @Inject constructor(
    private val api: WeatherApi,
) {

    suspend fun warningFor(at: LatLng): Outcome<WeatherWarning?> = outcomeOf {
        val grid = KmaGrid.toGrid(at)
        val (baseDate, baseTime) = latestNowcastBase()

        val items = retrying {
            api.ultraShortNowcast(
                baseDate = baseDate,
                baseTime = baseTime,
                nx = grid.nx,
                ny = grid.ny,
            ).itemsOrThrow()
        }

        val byCategory = items.associateBy(
            keySelector = { it.category.orEmpty() },
            valueTransform = { it.observedValue ?: it.forecastValue },
        )

        // T1H: 기온(℃), PTY: 강수형태, RN1: 1시간 강수량, WSD: 풍속(m/s)
        val temperature = byCategory["T1H"]?.toDoubleOrNull()
        val precipitationType = byCategory["PTY"]?.toIntOrNull() ?: 0
        val windSpeed = byCategory["WSD"]?.toDoubleOrNull()

        buildWarning(temperature, precipitationType, windSpeed)
    }

    private fun buildWarning(
        temperature: Double?,
        precipitationType: Int,
        windSpeed: Double?,
    ): WeatherWarning? {
        // 위험도가 높은 것부터 하나만 보여준다. 여러 개를 띄우면 아무것도 읽지 않는다.
        return when {
            precipitationType in listOf(3, 7) -> WeatherWarning(
                Severity.DANGER,
                "눈이 옵니다",
                "길이 미끄러워요. 미끄럼 방지 신발을 신으시고, 천천히 걸으세요.",
            )

            temperature != null && temperature <= -8 -> WeatherWarning(
                Severity.DANGER,
                "매우 춥습니다",
                "${temperature.toInt()}도입니다. 장갑과 모자를 꼭 챙기세요.",
            )

            temperature != null && temperature >= 33 -> WeatherWarning(
                Severity.DANGER,
                "매우 덥습니다",
                "${temperature.toInt()}도입니다. 물을 가지고 나가시고, 그늘에서 쉬어가세요.",
            )

            precipitationType in listOf(1, 2, 4, 5, 6) -> WeatherWarning(
                Severity.CAUTION,
                "비가 옵니다",
                "우산을 챙기세요. 바닥이 미끄럽습니다.",
            )

            temperature != null && temperature <= 0 -> WeatherWarning(
                Severity.CAUTION,
                "영하입니다",
                "${temperature.toInt()}도입니다. 따뜻하게 입으세요.",
            )

            windSpeed != null && windSpeed >= 9 -> WeatherWarning(
                Severity.CAUTION,
                "바람이 셉니다",
                "몸이 휘청일 수 있어요. 난간을 잡고 걸으세요.",
            )

            else -> null
        }
    }

    /**
     * 초단기실황 발표 시각을 구한다.
     *
     * 매시 정시 관측이 40분에 배포되므로, 40분 이전이면 한 시간 전 자료를 요청해야
     * NO_DATA 가 나지 않는다.
     */
    private fun latestNowcastBase(now: LocalDateTime = LocalDateTime.now()): Pair<String, String> {
        val base = if (now.minute < 40) now.minusHours(1) else now
        return base.format(DateTimeFormatter.ofPattern("yyyyMMdd")) to
            base.format(DateTimeFormatter.ofPattern("HH00"))
    }
}

enum class Severity { CAUTION, DANGER }

data class WeatherWarning(
    val severity: Severity,
    val headline: String,
    val advice: String,
)
