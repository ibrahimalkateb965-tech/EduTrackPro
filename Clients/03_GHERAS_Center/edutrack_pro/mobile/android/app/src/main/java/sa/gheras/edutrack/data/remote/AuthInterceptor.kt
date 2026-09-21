package sa.gheras.edutrack.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import sa.gheras.edutrack.data.local.session.SessionStore

class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val onUnauthorized: () -> Unit = {}
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Don't override if already has Authorization or is login request
        val requestBuilder = originalRequest.newBuilder()
        val token = sessionStore.token

        if (!token.isNullOrBlank() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val response = chain.proceed(requestBuilder.build())

        // Handle 401 session expiry
        if (response.code == 401) {
            onUnauthorized()
        }

        return response
    }
}
