package io.legado.app.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoServiceRegistrationTest {
    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun mimoServiceIsRegisteredForMediaPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, TTSMiMoAloudService::class.java),
            0
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
    }
}
