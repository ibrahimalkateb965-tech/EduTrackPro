package sa.gheras.edutrack.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.UnknownHostException

class ErrorMapperTest {

    @Test
    fun testMap_networkException_returnsNetworkError() {
        val err = ErrorMapper.map(UnknownHostException("Unable to resolve host"))
        assertTrue(err is AppError.Network)
        assertEquals("لا يوجد اتصال بالإنترنت — تحقق من الشبكة", err.userMessage)
    }

    @Test
    fun testMap_http401_returnsUnauthorized() {
        val body = "{\"detail\":\"بيانات الدخول غير صحيحة\"}".toResponseBody("application/json".toMediaType())
        val response = Response.error<Unit>(401, body)
        val err = ErrorMapper.map(HttpException(response))
        assertTrue(err is AppError.Unauthorized)
        assertEquals("بيانات الدخول غير صحيحة", err.userMessage)
    }

    @Test
    fun testMap_http403_returnsForbiddenWithServerMessage() {
        val body = "{\"message\":\"هذا التطبيق مخصص للمعلمين وأولياء الأمور\"}".toResponseBody("application/json".toMediaType())
        val response = Response.error<Unit>(403, body)
        val err = ErrorMapper.map(HttpException(response))
        assertTrue(err is AppError.Forbidden)
        assertEquals("هذا التطبيق مخصص للمعلمين وأولياء الأمور", err.userMessage)
    }

    @Test
    fun testMap_http500_returnsServerError() {
        val body = "Internal Server Error".toResponseBody("text/plain".toMediaType())
        val response = Response.error<Unit>(500, body)
        val err = ErrorMapper.map(HttpException(response))
        assertTrue(err is AppError.Server)
        assertEquals("تعذّر الاتصال بالخادم — حاول لاحقًا", err.userMessage)
    }
}
