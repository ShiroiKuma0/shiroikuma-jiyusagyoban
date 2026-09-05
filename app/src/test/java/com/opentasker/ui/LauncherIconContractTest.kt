package com.opentasker.ui

import com.opentasker.ProductionSources
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconContractTest {
    private val repoRoot: Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
        Path::getParent,
    ).first { Files.exists(it.resolve("app/src/main/AndroidManifest.xml")) }

    @Test
    fun `adaptive icons keep density color and themed layers separate`() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val adaptive = read("app/src/main/res/mipmap-anydpi-v26/$name")
            assertTrue("@color/ic_launcher_background" in adaptive)
            assertTrue("@mipmap/ic_launcher_foreground" in adaptive)
            assertTrue("@mipmap/ic_launcher_monochrome" in adaptive)
        }

        val expected = mapOf(
            "mipmap-mdpi" to 108,
            "mipmap-hdpi" to 162,
            "mipmap-xhdpi" to 216,
            "mipmap-xxhdpi" to 324,
            "mipmap-xxxhdpi" to 432,
        )

        expected.forEach { (directory, size) ->
            listOf("ic_launcher_foreground.png", "ic_launcher_monochrome.png").forEach { name ->
                val image = ImageIO.read(file("app/src/main/res/$directory/$name").toFile())
                assertEquals(size, image.width)
                assertEquals(size, image.height)
                assertEquals(0, image.getRGB(0, 0) ushr 24)
                assertTrue((image.getRGB(size / 2, size / 2) ushr 24) > 0)
            }
            assertFalse(Files.exists(file("app/src/main/res/$directory/ic_launcher.png")))
        }
        assertFalse(Files.exists(file("app/src/main/res/mipmap-ldpi/ic_launcher.png")))
        assertFalse(Files.exists(file("app/src/main/res/mipmap/ic_launcher_foreground.png")))
    }

    @Test
    fun `store and transparent copies match the checked in master`() {
        val primary = ImageIO.read(file("design/logo/source-user-logo-2026-08-29.png").toFile())
        assertEquals(1024, primary.width)
        assertEquals(1024, primary.height)
        assertEquals(255, primary.getRGB(0, 0) ushr 24)

        val store = ImageIO.read(file("fastlane/metadata/android/en-US/images/icon.png").toFile())
        assertEquals(512, store.width)
        assertEquals(512, store.height)
        assertEquals(primary.getRGB(0, 0) and 0xFFFFFF, store.getRGB(0, 0) and 0xFFFFFF)

        val foreground = ImageIO.read(file("design/logo/opentasker-mark.png").toFile())
        assertEquals(1024, foreground.width)
        assertEquals(1024, foreground.height)
        assertEquals(0, foreground.getRGB(0, 0) ushr 24)
        assertTrue((foreground.getRGB(foreground.width / 2, foreground.height / 2) ushr 24) > 0)
        assertFalse(Files.exists(file("design/logo/opentasker-mark.svg")))
    }

    @Test
    fun `notification silhouette keeps density sizes and real alpha`() {
        val expected = mapOf(
            "drawable-mdpi" to 24,
            "drawable-hdpi" to 36,
            "drawable-xhdpi" to 48,
            "drawable-xxhdpi" to 72,
            "drawable-xxxhdpi" to 96,
        )
        expected.forEach { (directory, size) ->
            val image = ImageIO.read(file("app/src/main/res/$directory/ic_notification.png").toFile())
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            assertEquals(0, image.getRGB(0, 0) ushr 24)
            assertTrue((image.getRGB(size / 2, size / 2) ushr 24) > 0)
        }
    }

    @Test
    fun `quick settings chooser uses the branded silhouette`() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse("@android:drawable/ic_menu_compass" in manifest)
        assertEquals(4, Regex("android:icon=\"@drawable/ic_notification\"").findAll(manifest).count())
    }

    @Test
    fun `no production surface borrows a framework drawable`() {
        // The manifest scan above missed two of these: SceneOverlayService built its foreground
        // notification in code and passed the stock compass, and the task widget's layout used
        // the stock media-play triangle. Matching only the call that broke first would have kept
        // missing the second, so this bans the reference outright. There are legitimately zero of
        // them, and the app ships its own glyph for every surface that needs one.
        // Two fork surfaces reference the framework set deliberately, and both are named rather
        // than pattern-matched so a third one is still a decision somebody writes down.
        val deliberate = mapOf(
            "app/src/main/java/com/opentasker/ui/screens/FrameworkIconPickerDialog.kt" to
                "the fork's icon picker exists to offer a curated android.R.drawable subset",
            "app/src/main/java/com/opentasker/core/bubbles/FlashBubbleOverlayManager.kt" to
                "sym_def_app_icon is the placeholder for an app whose own icon cannot be loaded",
        )
        val kotlinOffenders = ProductionSources.allKotlinFiles()
            .filter { path -> "android.R.drawable" in path.readText() }
            .map(::relative)
            .filterNot { it in deliberate }

        val resourceOffenders = Files.walk(file("app/src/main/res")).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".xml") }
                .filter { path -> "@android:drawable/" in path.readText() }
                .map(::relative)
                .toList()
        }

        assertTrue(
            "Every icon must come from this app, not the framework: ${kotlinOffenders + resourceOffenders}",
            (kotlinOffenders + resourceOffenders).isEmpty(),
        )
    }

    private fun relative(path: Path): String =
        ProductionSources.repoRoot.relativize(path).toString().replace('\\', '/')

    private fun file(relative: String): Path = repoRoot.resolve(relative)

    private fun read(relative: String): String = file(relative).readText()
}
