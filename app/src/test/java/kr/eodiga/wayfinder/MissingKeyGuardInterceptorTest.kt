package kr.eodiga.wayfinder

import io.mockk.every
import io.mockk.mockk
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.toFailureReason
import kr.eodiga.wayfinder.data.remote.MissingKeyGuardInterceptor
import kr.eodiga.wayfinder.data.remote.MissingServiceKeyException
import kr.eodiga.wayfinder.data.remote.ServiceKeyProvider
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MissingKeyGuardInterceptorTest {

    @Test
    fun `키가 없으면 OkHttp 경계의 IOException 으로 중단하고 설정 오류로 분류한다`() {
        val keys = mockk<ServiceKeyProvider>()
        every { keys.hasServiceKey } returns false
        val interceptor = MissingKeyGuardInterceptor(keys)

        try {
            interceptor.intercept(mockk<Interceptor.Chain>())
            fail("MissingServiceKeyException 이 발생해야 한다")
        } catch (error: MissingServiceKeyException) {
            assertTrue(error is IOException)
            assertEquals(FailureReason.NotConfigured, error.toFailureReason())
        }
    }
}
