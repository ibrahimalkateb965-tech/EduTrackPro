package sa.gheras.edutrack.data.local.session

import android.util.Base64
import org.json.JSONObject
import java.time.Instant

data class JwtPayload(
    val sub: String,
    val role: String,
    val jti: String,
    val iat: Long,
    val exp: Long
) {
    val expiresAt: Instant get() = Instant.ofEpochSecond(exp)
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)

    companion object {
        fun decode(token: String): JwtPayload? {
            return try {
                val parts = token.split(".")
                if (parts.size < 2) return null
                val payloadJson = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
                val json = JSONObject(payloadJson)
                JwtPayload(
                    sub = json.optString("sub"),
                    role = json.optString("role"),
                    jti = json.optString("jti"),
                    iat = json.optLong("iat"),
                    exp = json.optLong("exp")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
