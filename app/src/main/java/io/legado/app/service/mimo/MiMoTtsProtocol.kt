package io.legado.app.service.mimo

import com.google.gson.annotations.SerializedName

internal data class MiMoChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<MiMoMessage>,
    @SerializedName("audio") val audio: MiMoAudioRequest
)

internal data class MiMoMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

internal data class MiMoAudioRequest(
    @SerializedName("format") val format: String,
    @SerializedName("voice") val voice: String
)

internal data class MiMoChatResponse(@SerializedName("choices") val choices: List<MiMoChoice>?)

internal data class MiMoChoice(@SerializedName("message") val message: MiMoResponseMessage?)

internal data class MiMoResponseMessage(@SerializedName("audio") val audio: MiMoResponseAudio?)

internal data class MiMoResponseAudio(@SerializedName("data") val data: String?)

internal data class MiMoErrorResponse(@SerializedName("error") val error: MiMoApiError?)

internal data class MiMoApiError(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?
)
