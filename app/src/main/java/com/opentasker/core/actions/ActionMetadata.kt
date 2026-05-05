package com.opentasker.core.actions

/**
 * Metadata describing the arguments required/optional for an Action.
 * Used to build dynamic forms in the UI.
 */
data class ActionField(
    val key: String,                    // argument key in ActionSpec.args
    val label: String,                  // UI label
    val fieldType: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    val hint: String? = null,
)

enum class FieldType {
    TEXT,           // plain text input
    NUMBER,         // numeric input
    DROPDOWN,       // select from predefined values
    CHECKBOX,       // boolean toggle
    MULTILINE,      // multi-line text area
}

data class ActionMetadata(
    val id: String,                     // e.g. "notify.show"
    val name: String,                   // e.g. "Show Notification"
    val description: String,            // Human-readable description
    val category: String,               // e.g. "Notification", "Settings"
    val fields: List<ActionField> = emptyList(),
)

/**
 * Registry of action metadata for UI form generation.
 */
object ActionMetadataRegistry {
    private val byId = mutableMapOf<String, ActionMetadata>()

    fun register(metadata: ActionMetadata) {
        byId[metadata.id] = metadata
    }

    fun get(id: String): ActionMetadata? = byId[id]

    fun all(): Collection<ActionMetadata> = byId.values

    fun byCategory(category: String): List<ActionMetadata> =
        byId.values.filter { it.category == category }
}

// ============ Built-in Action Metadata ============

fun registerActionMetadata() {
    // Built-in actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.show",
            name = "Show Notification",
            description = "Display a toast or heads-up notification",
            category = "Notification",
            fields = listOf(
                ActionField("title", "Title", required = true, hint = "Notification title"),
                ActionField("text", "Message", FieldType.MULTILINE, hint = "Notification body"),
                ActionField("duration", "Duration", FieldType.DROPDOWN, hint = "Toast duration"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.set",
            name = "Set Variable",
            description = "Set a variable to a new value",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "%var name"),
                ActionField("value", "Value", required = true, hint = "Supports %expansion"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tts.speak",
            name = "Say (Text-to-Speech)",
            description = "Speak text aloud using the device speaker",
            category = "Notification",
            fields = listOf(
                ActionField("text", "Text to speak", FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.wait",
            name = "Wait",
            description = "Pause task execution for a specified duration",
            category = "Flow",
            fields = listOf(
                ActionField("millis", "Milliseconds", FieldType.NUMBER, required = true, hint = "Duration in ms"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "intent.launch",
            name = "Launch Intent",
            description = "Launch an activity or service via intent",
            category = "App",
            fields = listOf(
                ActionField("package", "Package name", required = true, hint = "com.example.app"),
                ActionField("action", "Intent action", hint = "MAIN, VIEW, etc."),
                ActionField("category", "Intent category", hint = "Optional"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "plugin.locale.fire",
            name = "Locale Plugin Setting",
            description = "Dispatch an explicit Locale/Tasker-compatible setting plugin request",
            category = "Plugin",
            fields = listOf(
                ActionField("package", "Plugin package", required = true, hint = "com.example.plugin"),
                ActionField("bundleJson", "Bundle JSON", FieldType.MULTILINE, hint = "{\"key\":\"value\"}"),
                ActionField("blurb", "Blurb", hint = "Short user-visible summary"),
                ActionField("timeoutMs", "Timeout ms", FieldType.NUMBER, hint = "5000"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "script.termux.run",
            name = "Run Termux Script",
            description = "Plan a Termux:Tasker script run; blocked until the script backend is implemented",
            category = "Script",
            fields = listOf(
                ActionField("executable", "Executable", required = true, hint = "~/.termux/tasker/my_script"),
                ActionField("arguments", "Arguments", FieldType.MULTILINE, hint = "Optional shell-style arguments"),
                ActionField("workingDirectory", "Working directory", hint = "Optional Termux working directory"),
                ActionField("stdin", "Standard input", FieldType.MULTILINE, hint = "Optional stdin payload"),
                ActionField("capturePrefix", "Output variable prefix", hint = "%script"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tasker.unsupported",
            name = "Unsupported Tasker Action",
            description = "Placeholder for a Tasker action that could not be safely mapped during import",
            category = "Import",
            fields = listOf(
                ActionField("taskerCode", "Tasker action code", required = true),
                ActionField("summary", "Import note", FieldType.MULTILINE),
            )
        )
    )

    // Settings actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wifi.toggle",
            name = "Toggle WiFi",
            description = "Turn WiFi on or off",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bluetooth.toggle",
            name = "Toggle Bluetooth",
            description = "Turn Bluetooth on or off",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "brightness.set",
            name = "Set Brightness",
            description = "Set screen brightness level",
            category = "Settings",
            fields = listOf(
                ActionField("level", "Brightness (0-255)", FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "volume.set",
            name = "Set Volume",
            description = "Adjust volume for a stream",
            category = "Settings",
            fields = listOf(
                ActionField("stream", "Stream", FieldType.DROPDOWN, required = true, hint = "music, call, alarm"),
                ActionField("level", "Level (0-100)", FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "airplane.toggle",
            name = "Toggle Airplane Mode",
            description = "Turn Airplane mode on or off",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "mobile.toggle",
            name = "Toggle Mobile Data",
            description = "Turn mobile data on or off",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.timeout",
            name = "Set Screen Timeout",
            description = "Set screen sleep timeout duration",
            category = "Settings",
            fields = listOf(
                ActionField("millis", "Timeout (ms)", FieldType.NUMBER, required = true, hint = "1000, 30000, etc."),
            )
        )
    )

    // App actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.launch",
            name = "Launch App",
            description = "Launch an installed application",
            category = "App",
            fields = listOf(
                ActionField("package", "Package name", required = true, hint = "com.example.app"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.kill",
            name = "Kill App",
            description = "Force close an app",
            category = "App",
            fields = listOf(
                ActionField("package", "Package name", required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "home.go",
            name = "Go Home",
            description = "Return to launcher home screen",
            category = "App",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "url.open",
            name = "Open URL",
            description = "Open a URL in the browser",
            category = "App",
            fields = listOf(
                ActionField("url", "URL", required = true, hint = "https://example.com"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sms.send",
            name = "Send SMS",
            description = "Send a text message",
            category = "App",
            fields = listOf(
                ActionField("number", "Phone number", required = true),
                ActionField("message", "Message", FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screenshot.take",
            name = "Take Screenshot",
            description = "Capture device screenshot",
            category = "App",
            fields = listOf(
                ActionField("filename", "Filename", hint = "optional output path"),
            )
        )
    )

    // File actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.read",
            name = "Read File",
            description = "Read file contents into a variable",
            category = "File",
            fields = listOf(
                ActionField("path", "File path", required = true),
                ActionField("variable", "Store in variable", required = true, hint = "%var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.write",
            name = "Write File",
            description = "Write contents to a file (overwrites)",
            category = "File",
            fields = listOf(
                ActionField("path", "File path", required = true),
                ActionField("content", "Content", FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.append",
            name = "Append to File",
            description = "Append contents to a file",
            category = "File",
            fields = listOf(
                ActionField("path", "File path", required = true),
                ActionField("content", "Content", FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.delete",
            name = "Delete File",
            description = "Delete a file",
            category = "File",
            fields = listOf(
                ActionField("path", "File path", required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.list",
            name = "List Files",
            description = "List directory contents into a variable",
            category = "File",
            fields = listOf(
                ActionField("path", "Directory path", required = true),
                ActionField("variable", "Store in variable", required = true, hint = "%var"),
            )
        )
    )

    // Network actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.get",
            name = "HTTP GET",
            description = "Perform an HTTP GET request",
            category = "Network",
            fields = listOf(
                ActionField("url", "URL", required = true),
                ActionField("variable", "Store response in", hint = "%var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.post",
            name = "HTTP POST",
            description = "Perform an HTTP POST request",
            category = "Network",
            fields = listOf(
                ActionField("url", "URL", required = true),
                ActionField("body", "Request body", FieldType.MULTILINE),
                ActionField("variable", "Store response in", hint = "%var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ping",
            name = "Ping Host",
            description = "Ping a network host",
            category = "Network",
            fields = listOf(
                ActionField("host", "Host address", required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "download",
            name = "Download File",
            description = "Download a file from URL",
            category = "Network",
            fields = listOf(
                ActionField("url", "URL", required = true),
                ActionField("path", "Save to path", required = true),
            )
        )
    )

    // Media actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.play",
            name = "Play Sound",
            description = "Play a sound file",
            category = "Media",
            fields = listOf(
                ActionField("path", "Sound file path", required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.stop",
            name = "Stop Sound",
            description = "Stop playback",
            category = "Media",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.pause",
            name = "Pause Sound",
            description = "Pause playback",
            category = "Media",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.next",
            name = "Next Track",
            description = "Play next track",
            category = "Media",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.previous",
            name = "Previous Track",
            description = "Play previous track",
            category = "Media",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "media.mute",
            name = "Mute",
            description = "Mute audio",
            category = "Media",
            fields = emptyList()
        )
    )

    // System actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "vibrate",
            name = "Vibrate",
            description = "Vibrate the device",
            category = "System",
            fields = listOf(
                ActionField("millis", "Duration (ms)", FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "reboot",
            name = "Reboot Device",
            description = "Reboot the device",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "lock",
            name = "Lock Device",
            description = "Lock the device screen",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.off",
            name = "Turn Screen Off",
            description = "Turn off the display",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wake",
            name = "Wake Device",
            description = "Wake the device (turn on screen)",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "log",
            name = "Log Message",
            description = "Write message to task log",
            category = "System",
            fields = listOf(
                ActionField("message", "Message", FieldType.MULTILINE, required = true),
            )
        )
    )
}
