package kr.eodiga.wayfinder.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kr.eodiga.wayfinder.data.remote.MissingServiceKeyException
import kr.eodiga.wayfinder.data.remote.dto.PublicDataException
import java.io.IOException

/**
 * 실패를 어르신에게 보여줄 말로 분류한다.
 * 화면에는 절대 스택트레이스나 영어 오류 코드를 띄우지 않는다.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val reason: FailureReason) : Outcome<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
}

enum class FailureReason(val spokenMessage: String) {
    /** 비행기모드, 지하 등. */
    NoNetwork("인터넷이 연결되지 않았습니다. 잠시 뒤 다시 해보세요."),

    /** 공공데이터 서버 지연. 지방 소도시 도착정보에서 흔하다. */
    ServerSlow("정보를 불러오는 데 시간이 걸립니다. 잠시만 기다려주세요."),

    /** 인증키 미설정/만료. 어르신이 해결할 수 없으므로 보호자에게 알리도록 안내한다. */
    NotConfigured("앱 설정에 문제가 있습니다. 가족에게 알려주세요."),

    /** 일일 트래픽 초과. */
    QuotaExceeded("오늘은 정보를 더 불러올 수 없습니다. 가족에게 알려주세요."),

    /** 조회는 됐지만 결과가 없음. 막차 이후, 벽지 노선 등. */
    NoResult("지금은 알 수 있는 정보가 없습니다."),

    /**
     * 위치 권한이 없거나 위치를 잡지 못함.
     *
     * [NoResult] 로 뭉뚱그리면 "그런 곳이 없다" 로 읽혀 보호자가 원인을
     * 찾을 수 없다. 이건 설정으로 고칠 수 있는 문제라 따로 말한다.
     */
    NoLocation("지금 계신 곳을 알 수 없습니다. 위치 사용을 허용해 주세요."),

    Unknown("잠시 문제가 있었습니다. 다시 해보세요.");
}

/** 예외를 [FailureReason] 으로 옮긴다. */
fun Throwable.toFailureReason(): FailureReason = when (this) {
    is MissingServiceKeyException -> FailureReason.NotConfigured
    is PublicDataException -> when {
        isQuotaExceeded -> FailureReason.QuotaExceeded
        isAuthError -> FailureReason.NotConfigured
        else -> FailureReason.ServerSlow
    }
    is IOException -> FailureReason.NoNetwork
    else -> FailureReason.Unknown
}

suspend inline fun <T> outcomeOf(crossinline block: suspend () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Outcome.Failure(e.toFailureReason())
    }

/**
 * 공공데이터 서버는 순간적으로 503/타임아웃을 자주 낸다.
 * 인증키·할당량 오류처럼 재시도가 무의미한 경우는 즉시 포기한다.
 */
suspend fun <T> retrying(
    attempts: Int = 3,
    initialDelayMs: Long = 400,
    block: suspend () -> T,
): T {
    var delayMs = initialDelayMs
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.toFailureReason()
            if (reason == FailureReason.NotConfigured || reason == FailureReason.QuotaExceeded) throw e
            delay(delayMs)
            delayMs *= 2
        }
    }
    return block()
}
