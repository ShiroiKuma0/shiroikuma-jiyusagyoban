package com.sysadmindoc.ruledeck.core.actions

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.AutomationService
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.SceneElement
import com.sysadmindoc.ruledeck.core.model.SceneElementType
import com.sysadmindoc.ruledeck.core.platform.PromotedOngoingNotificationSupport
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
    private val permissionProbePackageName = instrumentation.context.packageName
    private val permissionProbeContext = object : ContextWrapper(app) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.ACCESS_LOCAL_NETWORK) {
                packageManager.checkPermission(permission, permissionProbePackageName)
            } else {
                super.checkPermission(permission, pid, uid)
            }
    }

    @Before
    fun requireApi37Device() {
        assumeTrue("API 37+ device required", Build.VERSION.SDK_INT >= 37)
        // Successful action paths still perform real network operations as RuleDeck. The
        // separate androidTest package below owns the grant that the permission guard observes.
        grantRuntimePermission(packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    @After
    fun leavePermissionGrantedForEvidenceSnapshots() {
        if (Build.VERSION.SDK_INT < 37) return
        grantProbeLocalNetwork()
        grantRuntimePermission(packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
        app.stopService(Intent(app, AutomationService::class.java))
        SceneOverlayService.dismiss(app)
    }

    @Test
    fun localNetworkPermissionMatrixCoversGrantRevokeAndRegrant() = runBlocking {
        val deniedHttpUrl = "http://127.0.0.1:9/denied"

        revokeProbeLocalNetwork()
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

        grantProbeLocalNetwork()
        assertProbeLocalNetworkGranted()
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

        revokeProbeLocalNetwork()
        assertLocalNetworkDenied(PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))

        grantProbeLocalNetwork()
        assertProbeLocalNetworkGranted()
        assertEquals(ActionResult.Success, PingAction().run(ctx(), mapOf("host" to "127.0.0.1", "timeout_sec" to "1")))
    }

    @Test
    fun api37BackgroundServiceNotificationBluetoothOverlayAndBackSmoke() {
        grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        grantRuntimePermission(packageName, Manifest.permission.BLUETOOTH_SCAN)
        grantRuntimePermission(packageName, Manifest.permission.BLUETOOTH_CONNECT)
        shell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        shell("logcat -c")

        ContextCompat.startForegroundService(app, Intent(app, AutomationService::class.java))
        assertDumpContains("dumpsys activity services $packageName", "AutomationService")

        // Samsung's notification dump shortens channel ids ("rulede..."), even with --noredact,
        // so ask the notification service for this package's live notifications instead.
        // Android holds a new foreground service's notification back for up to 10 s
        // (deferred_fgs_notification_interval), so the wait has to outlast that.
        assertEngineNotificationPosted()

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
            "predictive/back smoke should not crash RuleDeck",
            !logcat.contains("FATAL EXCEPTION") || !logcat.contains(packageName),
        )
    }

    private fun assertEngineNotificationPosted(timeoutMs: Long = 15_000) {
        val manager = app.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (manager.activeNotifications.any { it.isEngineForegroundNotification() }) return
            Thread.sleep(250)
        }
        val posted = manager.activeNotifications.joinToString { "${it.id}:${it.notification.channelId}" }
        assertTrue(
            "expected a foreground-service notification on " +
                "${PromotedOngoingNotificationSupport.ENGINE_CHANNEL_ID}, posted: [$posted]",
            manager.activeNotifications.any { it.isEngineForegroundNotification() },
        )
    }

    private fun StatusBarNotification.isEngineForegroundNotification(): Boolean =
        notification.channelId == PromotedOngoingNotificationSupport.ENGINE_CHANNEL_ID &&
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    private fun ctx(variables: VariableStore = VariableStore()): ActionContext =
        ActionContext(permissionProbeContext, variables)

    private suspend fun assertLocalHttpSuccess() {
        val variables = VariableStore()
        withLoopbackHttpServer("ruledeck-api37-ok") { url ->
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
        assertEquals("ruledeck-api37-ok", variables.get("%api37_http"))
    }

    private fun assertLocalNetworkDenied(result: ActionResult) {
        assertTrue(
            "expected ACCESS_LOCAL_NETWORK failure, got $result",
            result is ActionResult.Failure && result.message.contains("ACCESS_LOCAL_NETWORK"),
        )
    }

    private fun assertProbeLocalNetworkGranted() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            app.packageManager.checkPermission(
                Manifest.permission.ACCESS_LOCAL_NETWORK,
                permissionProbePackageName,
            ),
        )
    }

    private fun grantProbeLocalNetwork() {
        grantRuntimePermission(permissionProbePackageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun revokeProbeLocalNetwork() {
        instrumentation.uiAutomation.revokeRuntimePermission(
            permissionProbePackageName,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
    }

    private fun grantRuntimePermission(targetPackage: String, permission: String) {
        instrumentation.uiAutomation.grantRuntimePermission(targetPackage, permission)
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
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Drain request headers before writing the fixed response. Closing this
                        // reader would also close the socket before its response can be written.
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Content-Type: text/plain\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            }
            runBlocking { block("http://127.0.0.1:${server.localPort}/api37") }
            worker.join(5_000)
            assertTrue("loopback HTTP server did not finish", !worker.isAlive)
        }
    }
}
