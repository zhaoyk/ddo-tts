package io.legado.app.service

import io.legado.app.utils.MD5Utils

internal object EdgeTtsConfig {
    const val NEUTRAL_RATE = "+0%"

    fun buildSsml(text: String, voice: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='+0Hz' rate='$NEUTRAL_RATE' volume='+0%'>$text</prosody>" +
                "</voice>" +
                "</speak>"

    fun cacheKey(voice: String, content: String): String =
        MD5Utils.md5Encode16(MD5Utils.md5Encode16("$voice\u0000$content"))
}
