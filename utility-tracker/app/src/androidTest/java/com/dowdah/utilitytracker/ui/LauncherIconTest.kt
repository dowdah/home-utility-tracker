package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.R
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/** Resource/render evidence is deliberately separate from the launcher's themed-icon support. */
@androidx.test.filters.MediumTest
class LauncherIconTest {
    @Test fun adaptiveLightDarkAndMonochromeRetainTransparentBoltInsideSafeCircle() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName.contains(".acceptance"))
        for ((mode, bg, fg) in listOf(
            Triple(Configuration.UI_MODE_NIGHT_NO, "#EAF7FF", "#006E8A"),
            Triple(Configuration.UI_MODE_NIGHT_YES, "#09252F", "#78D9EC"),
        )) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            })
            for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
                val icon = context.getDrawable(id)!!.mutate() as AdaptiveIconDrawable
                assertEquals(Color.parseColor(bg), render(icon.background).getPixel(54, 54))
                val foreground = render(icon.foreground)
                assertEquals(Color.parseColor(fg), foreground.getPixel(54, 80))
                assertEquals(0, Color.alpha(foreground.getPixel(54, 55)))
                val mono = render(checkNotNull(icon.monochrome))
                assertEquals(Color.BLACK, mono.getPixel(54, 80))
                assertEquals(0, Color.alpha(mono.getPixel(54, 55)))
                for ((name, bitmap) in listOf("foreground" to foreground, "mono" to mono)) {
                    File(base.getExternalFilesDir(null), "icon-$mode-$id-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                var maxDelta = 0
                var maxAt = ""
                for (y in 0..107) for (x in 0..107) {
                    val delta = kotlin.math.abs(Color.alpha(foreground.getPixel(x,y)) - Color.alpha(mono.getPixel(x,y)))
                    if (delta > maxDelta) { maxDelta = delta; maxAt = "$x,$y" }
                    if (Color.alpha(mono.getPixel(x,y)) > 0)
                        assertTrue("Mark outside 66dp safe circle at $x,$y", (x+0.5-54)*(x+0.5-54)+(y+0.5-54)*(y+0.5-54) <= 33*33)
                }
                android.util.Log.i("LauncherIconEvidence", "mode=$mode delta=$maxDelta at=$maxAt fg=${icon.foreground.javaClass.name} mono=${icon.monochrome?.javaClass?.name}")
                assertTrue("Alpha delta=$maxDelta at $maxAt", maxDelta <= 2)
                render(icon).let { bitmap ->
                    File(base.getExternalFilesDir(null), "icon-$mode-$id.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    bitmap.recycle()
                }
                foreground.recycle(); mono.recycle()
            }
        }
    }

    @Test fun packagedLegacyIconsCoverEveryDensityAndBothAppearances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.contains(".acceptance"))
        val sizes = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val images = zip.entries().asSequence().filter { it.name.matches(Regex("res/mipmap-(night-)?(m|h|xh|xxh|xxxh)dpi(-v[0-9]+)?/ic_launcher(_round)?\\.png")) }.toList()
            assertEquals(20, images.size)
            for (entry in images) {
                val density = checkNotNull(sizes.keys.firstOrNull { Regex("(?:^|-)${it}(?:-|/)").containsMatchIn(entry.name) })
                val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                assertEquals(sizes[density], bitmap.width)
                assertEquals(sizes[density], bitmap.height)
                assertTrue(Color.alpha(bitmap.getPixel(bitmap.width/2, bitmap.height/2)) > 0)
                bitmap.recycle()
            }
        }
    }

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888).also {
        // Separate vector caches: rendering the composite changes its children's bounds.
        val isolated = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        isolated.setBounds(0,0,108,108)
        isolated.draw(Canvas(it))
    }
}
