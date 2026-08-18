package dev.codexremote.app

import android.content.ComponentName
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityConfigurationInstrumentedTest {
    @Test
    @Suppress("DEPRECATION")
    fun mainActivityResizesForTheSoftwareKeyboard() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        val adjustMode = activityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustMode)
    }
}
