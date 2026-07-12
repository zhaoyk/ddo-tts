# MiMo Release JSON Protocol Stability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure MiMo TTS sends and parses its required JSON field names after R8 minification in the Release APK.

**Architecture:** Keep the wire protocol next to its DTO declarations by annotating every Gson-reflected property in `MiMoTtsProtocol.kt` with `@SerializedName`. Add a focused unit test that makes the field-name contract explicit, then verify the complete MiMo unit suite and a minified Release build.

**Tech Stack:** Kotlin, Gson, JUnit 4, Gradle/R8, Android APK.

## Global Constraints

- Preserve the existing Release R8 and resource shrinking configuration.
- Do not modify `app/build.gradle` signing logic.
- Keep the change scoped to MiMo network DTOs and their unit tests.
- Do not expose or log the MiMo API key.

---

## File Structure

- Modify: `app/src/main/java/io/legado/app/service/mimo/MiMoTtsProtocol.kt` — explicit JSON wire names for all MiMo request and response DTO fields.
- Create: `app/src/test/java/io/legado/app/service/mimo/MiMoTtsProtocolTest.kt` — protocol annotation and nested JSON serialization tests.

### Task 1: Lock the MiMo JSON contract with a failing test

**Files:**

- Create: `app/src/test/java/io/legado/app/service/mimo/MiMoTtsProtocolTest.kt`

**Interfaces:**

- Consumes: `MiMoChatRequest`, `MiMoMessage`, `MiMoAudioRequest`, `MiMoChatResponse`, `MiMoChoice`, `MiMoResponseMessage`, `MiMoResponseAudio`, `MiMoErrorResponse`, `MiMoApiError`.
- Produces: tests proving each Gson-reflected backing field has a stable wire name and a nested request serializes with that schema.

- [ ] **Step 1: Write the failing protocol annotation test**

```kotlin
package io.legado.app.service.mimo

import com.google.gson.annotations.SerializedName
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class MiMoTtsProtocolTest {
    @Test
    fun `every MiMo protocol field declares its JSON wire name`() {
        assertWireNames(MiMoChatRequest::class.java, mapOf("model" to "model", "messages" to "messages", "audio" to "audio"))
        assertWireNames(MiMoMessage::class.java, mapOf("role" to "role", "content" to "content"))
        assertWireNames(MiMoAudioRequest::class.java, mapOf("format" to "format", "voice" to "voice"))
        assertWireNames(MiMoChatResponse::class.java, mapOf("choices" to "choices"))
        assertWireNames(MiMoChoice::class.java, mapOf("message" to "message"))
        assertWireNames(MiMoResponseMessage::class.java, mapOf("audio" to "audio"))
        assertWireNames(MiMoResponseAudio::class.java, mapOf("data" to "data"))
        assertWireNames(MiMoErrorResponse::class.java, mapOf("error" to "error"))
        assertWireNames(MiMoApiError::class.java, mapOf("code" to "code", "message" to "message"))
    }

    @Test
    fun `nested MiMo request serializes with server field names`() {
        val payload = MiMoChatRequest(
            model = "mimo-v2.5-tts",
            messages = listOf(MiMoMessage("assistant", "正文")),
            audio = MiMoAudioRequest("wav", "冰糖")
        )

        assertEquals(
            """{"model":"mimo-v2.5-tts","messages":[{"role":"assistant","content":"正文"}],"audio":{"format":"wav","voice":"冰糖"}}""",
            GSON.toJson(payload)
        )
    }

    private fun assertWireNames(type: Class<*>, expected: Map<String, String>) {
        val actual = type.declaredFields
            .filterNot { it.isSynthetic }
            .associate { field ->
                field.name to field.getAnnotation(SerializedName::class.java)?.value
            }
        assertEquals(expected, actual)
    }
}
```

- [ ] **Step 2: Run the new test and verify the expected RED failure**

Run:

```bash
./gradlew :app:testAppDebugUnitTest --tests io.legado.app.service.mimo.MiMoTtsProtocolTest
```

Expected: `every MiMo protocol field declares its JSON wire name` fails because the DTO fields have no `SerializedName` annotation. The nested JSON assertion may already pass; that is expected because it does not simulate R8.

### Task 2: Preserve every MiMo DTO field name during minification

**Files:**

- Modify: `app/src/main/java/io/legado/app/service/mimo/MiMoTtsProtocol.kt:1-23`
- Test: `app/src/test/java/io/legado/app/service/mimo/MiMoTtsProtocolTest.kt`

**Interfaces:**

- Consumes: Gson `com.google.gson.annotations.SerializedName`.
- Produces: DTO fields whose serialized and deserialized JSON names remain fixed when R8 changes JVM field names.

- [ ] **Step 1: Add the Gson annotation import**

```kotlin
import com.google.gson.annotations.SerializedName
```

- [ ] **Step 2: Annotate every request, successful-response, and error-response property**

```kotlin
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
```

- [ ] **Step 3: Run the targeted test and verify GREEN**

Run:

```bash
./gradlew :app:testAppDebugUnitTest --tests io.legado.app.service.mimo.MiMoTtsProtocolTest
```

Expected: both tests pass.

- [ ] **Step 4: Commit the implementation and targeted test**

```bash
git add app/src/main/java/io/legado/app/service/mimo/MiMoTtsProtocol.kt app/src/test/java/io/legado/app/service/mimo/MiMoTtsProtocolTest.kt
git commit -m "fix: preserve MiMo JSON fields in release"
```

### Task 3: Verify the complete MiMo path and minified artifact

**Files:**

- Verify: `app/src/test/java/io/legado/app/service/mimo/*.kt`
- Verify: `app/build/outputs/apk/app/release/`

**Interfaces:**

- Consumes: the annotated DTOs from Task 2.
- Produces: a minified Release APK whose MiMo requests preserve the API schema.

- [ ] **Step 1: Run every MiMo unit test**

Run:

```bash
./gradlew :app:testAppDebugUnitTest --tests 'io.legado.app.service.mimo.*'
```

Expected: all MiMo tests pass.

- [ ] **Step 2: Build the minified Release APK**

Run:

```bash
./gradlew :app:assembleAppRelease --stacktrace
```

Expected: `BUILD SUCCESSFUL`; output APK exists at `app/build/outputs/apk/app/release/`.

- [ ] **Step 3: Install and reproduce on the connected phone**

Run:

```bash
adb install -r app/build/outputs/apk/app/release/legado_app_*.apk
```

Then choose MiMo TTS in the installed Release app and start reading one paragraph. Verify Logcat has no `MiMo TTS failed type=BadRequest` entry for the new attempt and playback starts.

- [ ] **Step 4: Commit verification-only changes if any exist**

No source changes are expected in this task. Do not create an empty commit.
