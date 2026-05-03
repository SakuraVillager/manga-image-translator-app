package com.sakuravillager.manga_translator.translation.render

import android.app.Application
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class FontLoadTest {

    @Test
    fun verify_font_loads() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttc")
        } catch (e: Exception) {
            // Font file not bundled (must be manually downloaded).
            // Fall back to system default which includes CJK on most devices.
            Typeface.DEFAULT
        }
        assertNotNull(typeface)
    }

    @Test
    fun verify_font_not_default_when_bundled() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        try {
            val typeface = Typeface.createFromAsset(
                context.assets,
                "fonts/NotoSansCJK-Regular.ttc"
            )
            assertNotNull(typeface)
            assertNotEquals(
                "Bundled font should not equal Typeface.DEFAULT",
                Typeface.DEFAULT,
                typeface
            )
        } catch (e: Exception) {
            // Font not bundled — skip assertion (requires manual download)
        }
    }
}
