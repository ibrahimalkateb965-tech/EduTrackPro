package sa.gheras.edutrack.data.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JwtPayloadTest {

    @Test
    fun testDecode_validToken_extractsClaimsSuccessfully() {
        // Payload: {"sub":"user_123","role":"teacher","jti":"jwt_456","iat":1789966596,"exp":1792558596}
        // Base64URL: eyJzdWIiOiJ1c2VyXzEyMyIsInJvbGUiOiJ0ZWFjaGVyIiwianRpIjoiand0XzQ1NiIsImlhdCI6MTc4OTk2NjU5NiwiZXhwIjoxNzkyNTU4NTk2fQ
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyXzEyMyIsInJvbGUiOiJ0ZWFjaGVyIiwianRpIjoiand0XzQ1NiIsImlhdCI6MTc4OTk2NjU5NiwiZXhwIjoxNzkyNTU4NTk2fQ.dummy_signature"

        val payload = JwtPayload.decode(token)
        assertNotNull(payload)
        assertEquals("user_123", payload?.sub)
        assertEquals("teacher", payload?.role)
        assertEquals("jwt_456", payload?.jti)
        assertEquals(1789966596L, payload?.iat)
        assertEquals(1792558596L, payload?.exp)
    }

    @Test
    fun testDecode_invalidToken_returnsNull() {
        assertNull(JwtPayload.decode("invalid.token"))
        assertNull(JwtPayload.decode(""))
    }
}
