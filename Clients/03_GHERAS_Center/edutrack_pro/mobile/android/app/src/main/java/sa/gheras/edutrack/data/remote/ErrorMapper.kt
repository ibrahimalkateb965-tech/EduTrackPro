package sa.gheras.edutrack.data.remote

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import sa.gheras.edutrack.data.remote.dto.ApiErrorBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed class AppError(open val userMessage: String, open val cause: Throwable? = null) {
    data class Network(override val userMessage: String = "لا يوجد اتصال بالإنترنت — تحقق من الشبكة", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class Unauthorized(override val userMessage: String = "بيانات الدخول غير صحيحة", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class Forbidden(override val userMessage: String = "ليس لديك صلاحية للوصول", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class NotFound(override val userMessage: String = "العنصر غير موجود", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class Validation(override val userMessage: String, override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class Server(override val userMessage: String = "تعذّر الاتصال بالخادم — حاول لاحقًا", override val cause: Throwable? = null) : AppError(userMessage, cause)
    data class Unknown(override val userMessage: String = "حدث خطأ غير متوقع", override val cause: Throwable? = null) : AppError(userMessage, cause)
}

object ErrorMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun map(throwable: Throwable): AppError {
        return when (throwable) {
            is UnknownHostException, is SocketTimeoutException -> AppError.Network(cause = throwable)
            is IOException -> AppError.Network(cause = throwable)
            is HttpException -> {
                val code = throwable.code()
                val serverMsg = extractServerMessage(throwable)

                when (code) {
                    401 -> AppError.Unauthorized(serverMsg ?: "بيانات الدخول غير صحيحة", cause = throwable)
                    403 -> AppError.Forbidden(serverMsg ?: "ليس لديك صلاحية للوصول", cause = throwable)
                    404 -> AppError.NotFound(serverMsg ?: "العنصر غير موجود", cause = throwable)
                    400, 422 -> AppError.Validation(serverMsg ?: "البيانات المدخلة غير صحيحة", cause = throwable)
                    in 500..599 -> AppError.Server(serverMsg ?: "تعذّر الاتصال بالخادم — حاول لاحقًا", cause = throwable)
                    else -> AppError.Unknown(serverMsg ?: "حدث خطأ في الخادم ($code)", cause = throwable)
                }
            }
            else -> AppError.Unknown(throwable.message ?: "حدث خطأ غير متوقع", cause = throwable)
        }
    }

    private fun extractServerMessage(exception: HttpException): String? {
        return try {
            val errorBody = exception.response()?.errorBody()?.string() ?: return null
            val apiError = json.decodeFromString<ApiErrorBody>(errorBody)
            apiError.message ?: apiError.detail ?: apiError.error
        } catch (e: Exception) {
            null
        }
    }
}
