package com.example.wifi_volume

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidResourceSmokeTest {
    @Test
    fun `resources are available in local JVM tests`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("wifi_volume", context.getString(R.string.app_name))
    }
}
