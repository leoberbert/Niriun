package com.niriun.app.net

import android.content.Context
import android.os.Build
import java.security.SecureRandom
import java.util.Locale

/** Stable identity announced in the hello, the beacon and the NSD service name. */
class DeviceIdentity(val id: ByteArray, val name: String) {
    /** 32 hex chars: the NSD service name (bytes in wire order). */
    val idHex: String get() = id.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

    companion object {
        private const val PREFS = "niriun_identity"
        private const val KEY_ID = "id"

        fun load(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_ID, null)
            val id = stored?.let(::fromHex)?.takeIf { it.size == 16 } ?: ByteArray(16).also {
                SecureRandom().nextBytes(it)
                prefs.edit().putString(KEY_ID, it.joinToString("") { b -> "%02x".format(Locale.ROOT, b.toInt() and 0xFF) }).apply()
            }
            return DeviceIdentity(id, displayName())
        }

        /** "Samsung SM-S918B": brand + model, brand skipped when the model already contains it. */
        fun displayName(brand: String = Build.BRAND, model: String = Build.MODEL): String {
            val full = if (model.contains(brand, ignoreCase = true)) model else "$brand $model"
            return full.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }

        private fun fromHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
        }
    }
}
