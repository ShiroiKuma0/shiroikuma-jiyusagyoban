package com.opentasker.core.permissions

/**
 * Pure, testable OEM battery-killer guidance.
 *
 * Aggressive OEM background management (Samsung "Put unused apps to sleep", Xiaomi/MIUI
 * background autostart, BBK/Oppo/Realme/Vivo startup managers, Huawei protected apps) is the
 * single most common reason Android automations silently stop firing. This helper maps a device
 * manufacturer/brand to a risk level, plain-language remediation steps, a dontkillmyapp.com
 * reference page, and candidate settings-screen components the UI can attempt to deep-link.
 *
 * It deliberately has no Android dependencies so it can be unit-tested and reused by both the
 * Setup screen and the Context Inspector source-health model.
 */
object OemBatteryGuidance {

    enum class RiskLevel {
        /** Stock-like behavior; battery-optimization exemption is generally enough. */
        LOW,

        /** Some additional toggles recommended (e.g. Samsung sleeping-apps exclusion). */
        MEDIUM,

        /** Background autostart is restricted by default and must be granted explicitly. */
        HIGH,

        /** Notoriously aggressive; multiple toggles required and reliability is still best-effort. */
        SEVERE,
    }

    /** A candidate OEM settings screen to deep-link to. The UI tries these in order. */
    data class SettingsTarget(
        val packageName: String,
        val className: String,
    )

    data class Guidance(
        /** Human-readable OEM family name, e.g. "Samsung One UI" or "Xiaomi (MIUI/HyperOS)". */
        val oemName: String,
        val riskLevel: RiskLevel,
        /** One-line risk summary for status rows. */
        val summary: String,
        /** Ordered remediation steps the user should follow. */
        val steps: List<String>,
        /** dontkillmyapp.com page for this OEM. */
        val dontKillMyAppUrl: String,
        /** Candidate autostart / background-management screens; may be empty. */
        val settingsTargets: List<SettingsTarget>,
    ) {
        /** True when the OEM needs more than the generic battery-optimization exemption. */
        val needsExtraSteps: Boolean
            get() = riskLevel != RiskLevel.LOW
    }

    private const val DKMA_BASE = "https://dontkillmyapp.com"

    /**
     * Resolve guidance for a device.
     *
     * @param manufacturer typically `Build.MANUFACTURER`
     * @param brand typically `Build.BRAND`; defaults to [manufacturer]
     */
    fun forDevice(manufacturer: String?, brand: String? = manufacturer): Guidance {
        val key = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase().trim()

        return when {
            key.containsAny("samsung") -> samsung()
            key.containsAny("xiaomi", "redmi", "poco", "miui", "hyperos") -> xiaomi()
            key.containsAny("oneplus") -> onePlus()
            key.containsAny("oppo") -> oppo()
            key.containsAny("realme") -> realme()
            key.containsAny("vivo", "iqoo") -> vivo()
            key.containsAny("huawei", "honor") -> huawei()
            key.containsAny("meizu") -> meizu()
            key.containsAny("asus") -> asus()
            // Stock-like OEMs: Google/Pixel, Sony, Motorola/Lenovo, Nokia/HMD, Fairphone.
            key.containsAny(
                "google", "pixel", "sony", "motorola", "lenovo",
                "nokia", "hmd", "fairphone",
            ) -> stock(manufacturer)
            else -> generic(manufacturer)
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun samsung() = Guidance(
        oemName = "Samsung One UI",
        riskLevel = RiskLevel.MEDIUM,
        summary = "One UI may put 白い熊 自由作業盤 to sleep. Exclude it from sleeping apps for reliable automation.",
        steps = listOf(
            "Settings > Battery and device care > Battery > Background usage limits.",
            "Turn off \"Put unused apps to sleep\" or add 白い熊 自由作業盤 to \"Never sleeping apps\".",
            "Remove 白い熊 自由作業盤 from \"Sleeping apps\" and \"Deep sleeping apps\" if listed.",
            "Settings > Apps > 白い熊 自由作業盤 > Battery: set to \"Unrestricted\".",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/samsung",
        settingsTargets = listOf(
            SettingsTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            SettingsTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
    )

    private fun xiaomi() = Guidance(
        oemName = "Xiaomi (MIUI/HyperOS)",
        riskLevel = RiskLevel.HIGH,
        summary = "MIUI/HyperOS blocks background autostart by default. Grant Autostart or automations will not fire.",
        steps = listOf(
            "Security > Permissions > Autostart: enable 白い熊 自由作業盤.",
            "Settings > Apps > 白い熊 自由作業盤 > Battery saver: set to \"No restrictions\".",
            "Recent apps: lock 白い熊 自由作業盤 (pull down on the card) so it is not killed.",
            "Disable \"MIUI optimization\" only if automations still fail after the above.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/xiaomi",
        settingsTargets = listOf(
            SettingsTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
    )

    private fun onePlus() = Guidance(
        oemName = "OnePlus (OxygenOS)",
        riskLevel = RiskLevel.HIGH,
        summary = "OxygenOS aggressively manages background apps. Disable optimization and enable auto-launch.",
        steps = listOf(
            "Settings > Battery > Battery optimization > 白い熊 自由作業盤: \"Don't optimize\".",
            "Settings > Apps > Auto-launch / Startup manager: enable 白い熊 自由作業盤 where present.",
            "Recent apps: lock 白い熊 自由作業盤 so deep optimization does not kill it.",
            "Disable \"Advanced optimization\" / \"Sleep standby optimization\" if available.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/oneplus",
        settingsTargets = listOf(
            SettingsTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ),
    )

    private fun oppo() = Guidance(
        oemName = "Oppo (ColorOS)",
        riskLevel = RiskLevel.SEVERE,
        summary = "ColorOS startup management blocks background apps. Enable auto-startup and allow background.",
        steps = listOf(
            "Settings > App management > 白い熊 自由作業盤 > Allow auto startup.",
            "Settings > Battery > 白い熊 自由作業盤 > Allow background activity / High background power.",
            "Phone Manager > Privacy permissions > Startup manager: enable 白い熊 自由作業盤.",
            "Recent apps: lock 白い熊 自由作業盤 so it is not cleared.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/oppo",
        settingsTargets = listOf(
            SettingsTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            SettingsTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            SettingsTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
    )

    private fun realme() = Guidance(
        oemName = "Realme (Realme UI)",
        riskLevel = RiskLevel.SEVERE,
        summary = "Realme UI (ColorOS-based) blocks background autostart. Enable startup and background activity.",
        steps = listOf(
            "Settings > App management > 白い熊 自由作業盤 > Allow auto startup.",
            "Settings > Battery > 白い熊 自由作業盤 > Allow background activity.",
            "Phone Manager > Startup manager: enable 白い熊 自由作業盤.",
            "Recent apps: lock 白い熊 自由作業盤 so it survives memory cleanup.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/realme",
        settingsTargets = listOf(
            SettingsTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            SettingsTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
    )

    private fun vivo() = Guidance(
        oemName = "Vivo (Funtouch/OriginOS)",
        riskLevel = RiskLevel.SEVERE,
        summary = "Vivo blocks background startup by default. Allow auto-start and high background power.",
        steps = listOf(
            "Settings > Battery > Background power consumption management: allow 白い熊 自由作業盤.",
            "i Manager > App manager > Autostart manager: enable 白い熊 自由作業盤.",
            "Settings > Apps > 白い熊 自由作業盤 > Battery: allow background activity.",
            "Recent apps: lock 白い熊 自由作業盤 so it is not cleared.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/vivo",
        settingsTargets = listOf(
            SettingsTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            SettingsTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
    )

    private fun huawei() = Guidance(
        oemName = "Huawei/Honor (EMUI/MagicOS)",
        riskLevel = RiskLevel.SEVERE,
        summary = "EMUI/MagicOS uses Protected apps and manual app launch. Set 白い熊 自由作業盤 to manage manually.",
        steps = listOf(
            "Settings > Apps > 白い熊 自由作業盤 > Battery > App launch: turn off \"Manage automatically\".",
            "Enable Auto-launch, Secondary launch, and Run in background manually.",
            "Phone Manager > Protected apps: enable 白い熊 自由作業盤 (older EMUI).",
            "Settings > Battery: disable aggressive power-saving modes.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/huawei",
        settingsTargets = listOf(
            SettingsTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            SettingsTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ),
    )

    private fun meizu() = Guidance(
        oemName = "Meizu (Flyme)",
        riskLevel = RiskLevel.HIGH,
        summary = "Flyme restricts background apps. Allow background running and standby for 白い熊 自由作業盤.",
        steps = listOf(
            "Settings > Apps > 白い熊 自由作業盤 > permissions: allow run in background.",
            "Phone Manager > Permissions > Background management: allow 白い熊 自由作業盤.",
            "Battery: disable standby intelligent power saving for 白い熊 自由作業盤.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/meizu",
        settingsTargets = listOf(
            SettingsTarget("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        ),
    )

    private fun asus() = Guidance(
        oemName = "Asus (ZenUI)",
        riskLevel = RiskLevel.MEDIUM,
        summary = "ZenUI Auto-start Manager can block 白い熊 自由作業盤. Allow auto-start and disable cleanup.",
        steps = listOf(
            "Auto-start Manager / Mobile Manager: allow 白い熊 自由作業盤.",
            "Settings > Battery: disable aggressive cleanup for 白い熊 自由作業盤.",
            "Settings > Apps > 白い熊 自由作業盤 > Battery: do not optimize.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/asus",
        settingsTargets = listOf(
            SettingsTarget("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"),
        ),
    )

    private fun stock(manufacturer: String?) = Guidance(
        oemName = manufacturer?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Stock Android",
        riskLevel = RiskLevel.LOW,
        summary = "This device follows stock Android battery behavior. The battery-optimization exemption is usually enough.",
        steps = listOf(
            "Settings > Apps > 白い熊 自由作業盤 > Battery: set to \"Unrestricted\".",
            "Keep the battery-optimization exemption granted above.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/general",
        settingsTargets = emptyList(),
    )

    private fun generic(manufacturer: String?) = Guidance(
        oemName = manufacturer?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Your device",
        riskLevel = RiskLevel.MEDIUM,
        summary = "This OEM is not specifically recognized. Disable battery optimization and look for an autostart toggle.",
        steps = listOf(
            "Keep the battery-optimization exemption granted above.",
            "Look for an \"Autostart\", \"Auto-launch\", or \"Background activity\" toggle in app or battery settings.",
            "Lock 白い熊 自由作業盤 in recent apps so the system does not clear it.",
        ),
        dontKillMyAppUrl = "$DKMA_BASE/general",
        settingsTargets = emptyList(),
    )
}
