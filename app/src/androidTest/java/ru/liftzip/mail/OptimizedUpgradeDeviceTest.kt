package ru.liftzip.mail

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OptimizedUpgradeDeviceTest {
    @Test fun preservesDataFromThePreviouslyDeliveredApk() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyOptimizedUpgrade") == "true")
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(File(app.filesDir, "upgrade-probe").isFile)
    }
}
