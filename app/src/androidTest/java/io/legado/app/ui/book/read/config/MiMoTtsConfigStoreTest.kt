package io.legado.app.ui.book.read.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.R
import io.legado.app.service.mimo.MiMoGlobalConfig
import io.legado.app.service.mimo.MiMoTtsConfigStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoTtsConfigStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clear() {
        context.getSharedPreferences(MiMoTtsConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun savesAndLoadsGlobalConfiguration() {
        val expected = MiMoGlobalConfig("key", "茉莉", "平静")
        MiMoTtsConfigStore.saveGlobal(context, expected)
        assertEquals(expected, MiMoTtsConfigStore.loadGlobal(context))
    }

    @Test
    fun voiceResourceValuesMatchSupportedVoiceOrder() {
        val values = context.resources.getStringArray(R.array.mimo_voice_values).toList()
        assertEquals(MiMoTtsConfigStore.supportedVoices.toList(), values)
    }
}
