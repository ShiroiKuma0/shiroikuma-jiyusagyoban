package com.opentasker.ui

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
    fun `adaptive icons keep color and themed layers separate`() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val adaptive = read("app/src/main/res/mipmap/$name")
            assertTrue("@color/ic_launcher_background" in adaptive)
            assertTrue("@drawable/ic_opentasker_mark" in adaptive)
            assertTrue("@drawable/ic_opentasker_mark_monochrome" in adaptive)
        }

        val foreground = read("app/src/main/res/drawable/ic_opentasker_mark.xml")
        assertTrue("#FBFBFB" in foreground)
        assertTrue("#13A8D5" in foreground)
        assertFalse("The adaptive foreground must not bake in its background", "#0C172E" in foreground)

        val monochrome = read("app/src/main/res/drawable/ic_opentasker_mark_monochrome.xml")
        assertTrue("#FFFFFFFF" in monochrome)
        assertFalse("#13A8D5" in monochrome)
    }

    @Test
    fun `legacy launchers receive the exact density sizes`() {
        val expected = mapOf(
            "mipmap-ldpi" to 36,
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192,
        )

        expected.forEach { (directory, size) ->
            val image = ImageIO.read(file("app/src/main/res/$directory/ic_launcher.png").toFile())
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            assertEquals(0x0C172E, image.getRGB(0, 0) and 0xFFFFFF)
        }
    }

    @Test
    fun `store and transparent masters match the launcher contract`() {
        val store = ImageIO.read(file("fastlane/metadata/android/en-US/images/icon.png").toFile())
        assertEquals(512, store.width)
        assertEquals(512, store.height)
        assertEquals(0x0C172E, store.getRGB(0, 0) and 0xFFFFFF)

        val foreground = ImageIO.read(file("design/logo/opentasker-mark.png").toFile())
        assertEquals(1024, foreground.width)
        assertEquals(1024, foreground.height)
        assertEquals(0, foreground.getRGB(0, 0) ushr 24)
        assertTrue((foreground.getRGB(foreground.width * 3 / 4, foreground.height * 44 / 100) ushr 24) > 0)
    }

    @Test
    fun `quick settings chooser uses the branded silhouette`() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse("@android:drawable/ic_menu_compass" in manifest)
        assertEquals(4, Regex("android:icon=\"@drawable/ic_notification\"").findAll(manifest).count())
    }

    private fun file(relative: String): Path = repoRoot.resolve(relative)

    private fun read(relative: String): String = file(relative).readText()
}
