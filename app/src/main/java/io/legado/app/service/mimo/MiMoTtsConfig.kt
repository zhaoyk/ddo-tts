package io.legado.app.service.mimo

import android.content.Context
import androidx.core.content.edit
import io.legado.app.data.entities.Book

object MiMoTtsContract {
    const val ENGINE_VALUE = "mimo"
    const val MODEL = "mimo-v2.5-tts"
    const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
    const val DEFAULT_VOICE = "mimo_default"
    const val ACTION_CONFIG_CHANGED = "io.legado.app.action.MIMO_TTS_CONFIG_CHANGED"
}

data class MiMoGlobalConfig(
    val apiKey: String,
    val voice: String,
    val style: String
) {
    override fun toString(): String =
        "MiMoGlobalConfig(apiKey=<redacted>, voice=$voice, style=$style)"
}

data class MiMoTtsConfig(
    val apiKey: String,
    val voice: String,
    val style: String
) {
    override fun toString(): String =
        "MiMoTtsConfig(apiKey=<redacted>, voice=$voice, style=$style)"
}

object MiMoTtsConfigStore {
    const val PREFS_NAME = "TTS_CONFIG"
    const val KEY_API_KEY = "tts_mimo_api_key"
    const val KEY_VOICE = "tts_mimo_voice"
    const val KEY_STYLE = "tts_mimo_style"

    val supportedVoices: Set<String> = linkedSetOf(
        MiMoTtsContract.DEFAULT_VOICE,
        "冰糖",
        "茉莉",
        "苏打",
        "白桦",
        "Mia",
        "Chloe",
        "Milo",
        "Dean"
    )

    fun loadGlobal(context: Context): MiMoGlobalConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return MiMoGlobalConfig(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim(),
            voice = normalizeVoice(prefs.getString(KEY_VOICE, MiMoTtsContract.DEFAULT_VOICE)),
            style = prefs.getString(KEY_STYLE, "").orEmpty().trim()
        )
    }

    fun saveGlobal(context: Context, config: MiMoGlobalConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_API_KEY, config.apiKey.trim())
            putString(KEY_VOICE, normalizeVoice(config.voice))
            putString(KEY_STYLE, config.style.trim())
        }
    }

    fun resolve(context: Context, book: Book?): MiMoTtsConfig =
        resolve(loadGlobal(context), book?.getMiMoTtsStyle())

    internal fun resolve(global: MiMoGlobalConfig, bookStyle: String?): MiMoTtsConfig {
        val effectiveBookStyle = bookStyle?.trim().orEmpty()
        return MiMoTtsConfig(
            apiKey = global.apiKey.trim(),
            voice = normalizeVoice(global.voice),
            style = effectiveBookStyle.ifEmpty { global.style.trim() }
        )
    }

    fun normalizeVoice(voice: String?): String {
        val normalized = voice?.trim().orEmpty()
        return normalized.takeIf(supportedVoices::contains) ?: MiMoTtsContract.DEFAULT_VOICE
    }
}
