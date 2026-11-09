package com.haokai.addemo.device

import android.content.Context
import android.content.SharedPreferences

class DeviceSessionStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): DeviceSession? {
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val exp = prefs.getLong(KEY_EXPIRES, 0L)
        if (id.isBlank() || token.isBlank()) return null
        return DeviceSession(id, token, exp)
    }

    fun save(session: DeviceSession) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, session.smartDeviceId)
            .putString(KEY_TOKEN, session.deviceToken)
            .putLong(KEY_EXPIRES, session.tokenExpiresAtEpochMs)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis(), skewMs: Long = 60_000L): Boolean {
        val s = load() ?: return true
        return s.tokenExpiresAtEpochMs > 0 && nowMs >= s.tokenExpiresAtEpochMs - skewMs
    }

    companion object {
        private const val PREFS = "device_session"
        private const val KEY_DEVICE_ID = "smartDeviceId"
        private const val KEY_TOKEN = "deviceToken"
        private const val KEY_EXPIRES = "tokenExpiresAt"
    }
}
