package sa.gheras.edutrack.data.remote.fakes

import retrofit2.Response
import sa.gheras.edutrack.data.remote.AuthApi
import sa.gheras.edutrack.data.remote.dto.ChangePasswordBody
import sa.gheras.edutrack.data.remote.dto.LoginBody
import sa.gheras.edutrack.data.remote.dto.LoginResponse
import sa.gheras.edutrack.data.remote.dto.ProfileUserDto
import sa.gheras.edutrack.data.remote.dto.StatusResponse

class FakeAuthApi : AuthApi {
    var shouldFailLogin = false
    var loginUserRole = "teacher"

    override suspend fun login(body: LoginBody): LoginResponse {
        if (shouldFailLogin) {
            throw IllegalStateException("Invalid credentials")
        }
        return LoginResponse(
            token = "fake.jwt.token",
            user = ProfileUserDto(
                id = "user_1",
                username = body.username,
                role = loginUserRole,
                name = "Test User"
            )
        )
    }

    override suspend fun logout(): Response<Unit> {
        return Response.success(Unit)
    }

    override suspend fun changePassword(body: ChangePasswordBody): StatusResponse {
        return StatusResponse(status = "ok", message = "تم تغيير كلمة المرور")
    }
}
