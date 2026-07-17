package com.opentasker.core.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.scenes.SceneOverlayService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class Api37LocalNetworkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext.applicationContext
    private val packageName = app.packageName

    @Before
    fun requireApi37Device() {
        assumeTrue("API 37+ device required", Build.VERSION.SDK_INT >= 37)
    }

    @After
    fun leavePermissionGrantedForEvidenceSnapshots() {
        grantLocalNetwork()
    }

    @Test
    fun localNetworkPermissionMatrixCoversGrantRevokeAndRegrant() = runBlocking {
        val deniedHttpUrl = "http://127.0.0.1:9/denied"

        revokeLocalNetwork()
        assertLocalNetworkDenied(HttpGetAction().run(ctx(), mapOf("url" to deniedHttpUrl, "allow_http" to "true")))
        assertLocalNetworkDenied(PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))
        assertLocalNetworkDenied(
            WakeOnLanAction().run(
                ctx(),
                mapOf(
                    "mac" to "AA:BB:CC:DD:EE:FF",
                    "broadcast" to "127.0.0.1",
                    "port" to "9",
                ),
            ),
        )

        grantLocalNetwork()
        assertLocalNetworkGranted()
        assertLocalHttpSuccess()
        assertEquals(ActionResult.Success, PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))
        assertEquals(
            ActionResult.Success,
            WakeOnLanAction().run(
                ctx(),
                mapOf(
                    "mac" to "AA:BB:CC:DD:EE:FF",
                    "broadcast" to "127.0.0.1",
                    "port" to "9",
                ),
            ),
        )

        revokeLocalNetwork()
        assertLocalNetworkDenied(PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))

        grantLocalNetwork()
        assertLocalNetworkGranted()
        assertEquals(ActionResult.Success, PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))
    }

    @Test
    fun api37BackgroundServiceNotificationBluetoothOverlayAndBackSmoke() {
        grantRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        grantRuntimePermission(Manifest.permission.BLUETOOTH_SCAN)
        grantRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
        shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        shell("logcat -c")

        ContextCompat.startForegroundService(app, Intent(app, AutomationService::class.java))
        assertDumpContains("dumpsys activity services $packageName", "AutomationService")

        val notificationDump = shell("dumpsys notification --noredact")
        assertTrue(
            "expected foreground-service notification evidence",
            notificationDump.contains("OpenTasker is running") || notificationDump.contains("opentasker.engine"),
        )

        val bluetoothDump = shell("dumpsys bluetooth_manager")
        assertTrue("expected Bluetooth service dump", bluetoothDump.isNotBlank())

        assertTrue("overlay app-op should allow drawing overlays", Settings.canDrawOverlays(app))
        SceneOverlayService.show(
            app,
            Scene(
                id = 37_001,
                name = "API 37 overlay smoke",
                widthDp = 220,
                heightDp = 120,
                elements = listOf(
                    SceneElement(
                        id = 1,
                        type = SceneElementType.TEXT,
                        xDp = 8,
                        yDp = 8,
                        widthDp = 200,
                        heightDp = 40,
                        config = mapOf("text" to "API 37 overlay smoke"),
                    ),
                ),
            ),
        )
        assertDumpContains("dumpsys activity services $packageName", "SceneOverlayService")

        shell("am start -W -n $packageName/com.opentasker.app.MainActivity")
        shell("input keyevent KEYCODE_BACK")
        val logcat = shell("logcat -d -t 200")
        assertTrue(
            "predictive/back smoke should not crash OpenTasker",
            !logcat.contains("FATAL EXCEPTION") || !logcat.contains(packageName),
        )
    }

    private fun ctx(variables: VariableStore = VariableStore()): ActionContext =
        ActionContext(app, variables)

    private suspend fun assertLocalHttpSuccess() {
        val variables = VariableStore()
        withLoopbackHttpServer("opentasker-api37-ok") { url ->
            val result = HttpGetAction().run(
                ctx(variables),
                mapOf(
                    "url" to url,
                    "allow_http" to "true",
                    "var" to "%api37_http",
                    "timeout_sec" to "5",
                ),
            )
            assertEquals(ActionResult.Success, result)
        }
        assertEquals("opentasker-api37-ok", variables.get("%api37_http"))
    }

    private fun assertLocalNetworkDenied(result: ActionResult) {
        assertTrue(
            "expected ACCESS_LOCAL_NETWORK failure, got $result",
            result is ActionResult.Failure && result.message.contains("ACCESS_LOCAL_NETWORK"),
        )
    }

    private fun assertLocalNetworkGranted() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK),
        )
    }

    private fun grantLocalNetwork() {
        grantRuntimePermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun revokeLocalNetwork() {
        shell("pm revoke $packageName ${Manifest.permission.ACCESS_LOCAL_NETWORK}")
        shell("pm clear-permission-flags $packageName ${Manifest.permission.ACCESS_LOCAL_NETWORK} user-set user-fixed")
    }

    private fun grantRuntimePermission(permission: String) {
        shell("pm grant $packageName $permission")
    }

    private fun assertDumpContains(command: String, token: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = ""
        while (System.currentTimeMillis() < deadline) {
            latest = shell(command)
            if (latest.contains(token)) return
            Thread.sleep(250)
        }
        assertTrue("expected '$token' in '$command' output:\n$latest", latest.contains(token))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }

    private fun withLoopbackHttpServer(body: String, block: suspend (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(start = true, name = "api37-loopback-http") {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().use { reader ->
                        while (reader.readLine()?.isNotEmpty() == true) {
                            // Drain request headers before writing the fixed response.
                        }
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
                        output.write("Content-Type: text/plain\r\n".toByteArray(Charsets.US_ASCII))
                        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                        output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                    }
                }
            }
            runBlocking { block("http://127.0.0.1:${server.localPort}/api37") }
            worker.join(5_000)
            assertTrue("loopback HTTP server did not finish", !worker.isAlive)
        }
    }
}
