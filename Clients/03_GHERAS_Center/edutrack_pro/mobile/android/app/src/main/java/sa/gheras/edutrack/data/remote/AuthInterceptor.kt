package sa.gheras.edutrack.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import sa.gheras.edutrack.data.local.session.SessionStore

class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val allowedHost: String? = null,
    private val onUnauthorized: () -> Unit = {}
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Don't override if already has Authorization or is login request
        val requestBuilder = originalRequest.newBuilder()
        val token = sessionStore.token

        val host = originalRequest.url.host
        val isAllowedHost = allowedHost?.equals(host, ignoreCase = true) ?: isKnownInternalHost(host)

        if (isAllowedHost && !token.isNullOrBlank() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = chain.proceed(requestBuilder.build())

        // Handle 401 session expiry
        if (response.code == 401) {
            onUnauthorized()
        }

        return response
    }

    private fun isKnownInternalHost(host: String): Boolean {
        return host.equals("gheras.autovem.tech", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "10.0.2.2" ||
            host == "localhost"
    }
}
