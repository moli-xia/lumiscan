package com.lumiscan.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.lumiscan.app.ScanApp
import com.lumiscan.app.data.remote.Api
import com.lumiscan.app.data.remote.ApiException
import com.lumiscan.app.data.remote.AppConfig
import com.lumiscan.app.data.remote.AuthResponse
import com.lumiscan.app.data.remote.Profile
import com.lumiscan.app.data.remote.QuotaInfo
import kotlinx.serialization.json.Json

/**
 * The signed-in user. The token and last known profile are cached so the app opens straight
 * into its (mostly on-device) features when offline; the server is re-checked on every launch.
 */
class Account(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    var token by mutableStateOf(prefs.getString("token", null))
        private set
    var profile by mutableStateOf(prefs.getString("profile", null)?.let { runCatching { json.decodeFromString(Profile.serializer(), it) }.getOrNull() })
        private set
    var config by mutableStateOf<AppConfig?>(null)
        private set

    /** Why the user was signed out (expired / signed in elsewhere / banned), shown on the login page. */
    var signOutReason by mutableStateOf<String?>(null)

    val loggedIn: Boolean get() = token != null

    init {
        Api.token = token
        Api.onUnauthorized = { reason -> if (token != null) clear(reason) }
    }

    suspend fun login(username: String, password: String) = save(Api.login(username, password))

    suspend fun register(username: String, password: String, inviteCode: String?) = save(Api.register(username, password, inviteCode))

    /** Re-reads the profile (group, quota). Network failures keep the cached one. */
    suspend fun refresh(): Profile? = try {
        Api.me().also { storeProfile(it) }
    } catch (e: ApiException) {
        null
    }

    suspend fun loadConfig(): AppConfig? = runCatching { Api.config() }.getOrNull()?.also { config = it }

    suspend fun updateProfile(nickname: String? = null, email: String? = null) = storeProfile(Api.updateProfile(nickname, email))

    suspend fun changePassword(old: String, new: String) = Api.changePassword(old, new)

    suspend fun deleteAccount(password: String) {
        Api.deleteAccount(password)
        clear(null)
    }

    suspend fun logout() {
        runCatching { Api.logout() }
        clear(null)
    }

    /** AI responses carry the updated quota so the remaining count stays current without a refetch. */
    fun updateQuota(q: QuotaInfo?) {
        val p = profile ?: return
        if (q != null) storeProfile(p.copy(quota = q))
    }

    private fun save(r: AuthResponse) {
        token = r.token
        Api.token = r.token
        signOutReason = null
        prefs.edit { putString("token", r.token) }
        storeProfile(r.profile)
    }

    private fun storeProfile(p: Profile) {
        profile = p
        prefs.edit { putString("profile", json.encodeToString(Profile.serializer(), p)) }
    }

    private fun clear(reason: String?) {
        token = null
        Api.token = null
        profile = null
        signOutReason = reason
        prefs.edit { remove("token"); remove("profile") }
        ScanApp.instance.navigator.reset()
    }
}
