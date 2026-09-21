package sa.gheras.edutrack.data.local.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import java.io.File
import java.time.Instant

class EncryptedPrefsSessionStore(private val context: Context) : SessionStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs: SharedPreferences by lazy {
        createOrRecoverPrefs()
    }

    private fun createOrRecoverPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Self-heal: Keystore key mismatch / corrupted prefs -> wipe prefs file and re-create
            try {
                val prefsFile = File(context.filesDir.parent, "shared_prefs/$PREFS_FILE_NAME.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                }
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e2: Exception) {
                // Fallback to standard prefs if Keystore hardware is completely unavailable in test/emulator
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    override val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    override val tokenExp: Instant?
        get() {
            val exp = prefs.getLong(KEY_TOKEN_EXP, -1L)
            return if (exp > 0) Instant.ofEpochSecond(exp) else null
        }

    override val user: SessionUser?
        get() {
            val userJson = prefs.getString(KEY_USER_JSON, null) ?: return null
            return try {
                val orgJson = org.json.JSONObject(userJson)
                val roleStr = orgJson.optString("role", "TEACHER").uppercase()
                val role = if (roleStr.contains("GUARDIAN")) Role.GUARDIAN else Role.TEACHER
                SessionUser(
                    id = orgJson.getString("id"),
                    username = orgJson.getString("username"),
                    role = role,
                    name = orgJson.optString("name", orgJson.getString("username"))
                )
            } catch (e: Exception) {
                null
            }
        }

    override val profile: ProfileDto?
        get() {
            val profileJson = prefs.getString(KEY_PROFILE_JSON, null) ?: return null
            return try {
                json.decodeFromString<ProfileDto>(profileJson)
            } catch (e: Exception) {
                null
            }
        }

    override val lastUserId: String?
        get() = prefs.getString(KEY_LAST_USER_ID, null)

    override fun saveLogin(token: String, user: SessionUser) {
        val jwt = JwtPayload.decode(token)
        val exp = jwt?.exp ?: -1L

        val userJsonObj = org.json.JSONObject().apply {
            put("id", user.id)
            put("username", user.username)
            put("role", user.role.name)
            put("name", user.name)
        }

        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_EXP, exp)
            .putString(KEY_USER_JSON, userJsonObj.toString())
            .putString(KEY_LAST_USER_ID, user.id)
            .commit() // Commit synchronously per design spec §4.1
    }

    override fun saveProfile(profile: ProfileDto) {
        val serialized = json.encodeToString(profile)
        prefs.edit().putString(KEY_PROFILE_JSON, serialized).apply()
    }

    override fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_TOKEN_EXP)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS_FILE_NAME = "gheras_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_EXP = "token_exp"
        private const val KEY_USER_JSON = "user_json"
        private const val KEY_PROFILE_JSON = "profile_json"
        private const val KEY_LAST_USER_ID = "last_user_id"
    }
}
