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
            description = "Post a notification with channel, persistence, and replacement controls",
            category = "Notification",
            fields = listOf(
                ActionField("title", "Title", required = true, hint = "Notification title"),
                ActionField("text", "Message", FieldType.MULTILINE, hint = "Notification body"),
                ActionField("channel", "Channel", FieldType.DROPDOWN, hint = "quiet / default / urgent"),
                ActionField("persistent", "Persistent", FieldType.CHECKBOX, hint = "Keep until cancelled"),
                ActionField("tag", "Tag", hint = "Replacement tag (same tag replaces)"),
                ActionField("id", "ID", FieldType.NUMBER, hint = "Notification ID (same ID replaces)"),
                ActionField("button1_label", "Button 1 label", hint = "Action button label"),
                ActionField("button1_task", "Button 1 task", hint = "Task name to run on tap"),
                ActionField("button2_label", "Button 2 label", hint = "Second button label"),
                ActionField("button2_task", "Button 2 task", hint = "Task name to run on tap"),
                ActionField("button3_label", "Button 3 label", hint = "Third button label"),
                ActionField("button3_task", "Button 3 task", hint = "Task name to run on tap"),
            )
        )
    )
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.cancel",
            name = "Cancel Notification",
            description = "Cancel a notification by tag and/or ID",
            category = "Notification",
            fields = listOf(
                ActionField("tag", "Tag", hint = "Notification tag to cancel"),
                ActionField("id", "ID", FieldType.NUMBER, hint = "Notification ID to cancel"),
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
            id = "var.clear",
            name = "Variable Clear",
            description = "Unset a variable (and any array of the same name)",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "bare name, no %"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.split",
            name = "Variable Split",
            description = "Split a variable's value into an array of the same name",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "bare name, no %"),
                ActionField("splitter", "Splitter", hint = "delimiter; \\n \\t allowed; empty = per character"),
                ActionField("delete_base", "Delete base", FieldType.CHECKBOX, hint = "Unset the scalar after splitting"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.join",
            name = "Variable Join",
            description = "Join the array of this name back into a single value",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
                ActionField("joiner", "Joiner", hint = "delimiter; \\n \\t allowed"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.replace",
            name = "Variable Search Replace",
            description = "Regex search/replace within a variable; optionally store matches",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "bare name, no %"),
                ActionField("search", "Search", required = true, hint = "regular expression"),
                ActionField("replace", "Replace with", hint = "replacement (empty = remove matches)"),
                ActionField("ignore_case", "Ignore case", FieldType.CHECKBOX),
                ActionField("multiline", "Multi-line", FieldType.CHECKBOX, hint = "^ and $ match per line"),
                ActionField("store_matches", "Store matches in", hint = "array variable for matches (optional)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.convert",
            name = "Variable Convert",
            description = "Transform a variable's value (case, encoding, hash, …)",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "bare name, no %"),
                ActionField("function", "Function", FieldType.DROPDOWN, required = true,
                    hint = "upper / lower / trim / length / reverse / capitalize / urlencode / urldecode / base64encode / base64decode / md5 / sha1 / sha256"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.add",
            name = "Variable Add",
            description = "Add a number to a numeric variable, with optional wrap and round",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Variable name", required = true, hint = "bare name, no %"),
                ActionField("value", "Amount", FieldType.NUMBER, required = true, hint = "number to add (may be negative)"),
                ActionField("wrap", "Wrap around", FieldType.NUMBER, hint = "wrap result modulo this value (optional)"),
                ActionField("round", "Round", FieldType.CHECKBOX, hint = "round result to a whole number"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime",
            name = "Parse/Format DateTime",
            description = "Produce a formatted date/time string into a variable",
            category = "Variable",
            fields = listOf(
                ActionField("store", "Store result in", required = true, hint = "bare variable name"),
                ActionField("source", "Source", FieldType.DROPDOWN, hint = "now / seconds / millis / formatted"),
                ActionField("input", "Input", hint = "epoch value, or a date string when source = formatted"),
                ActionField("inputformat", "Input format", hint = "pattern when source = formatted (e.g. yyyy-MM-dd)"),
                ActionField("format", "Output format", hint = "default yyyy-MM-dd HH:mm:ss"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.set",
            name = "Array Set",
            description = "Populate an array from a delimited string",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
                ActionField("values", "Values", FieldType.MULTILINE, hint = "delimited string"),
                ActionField("splitter", "Splitter", hint = "delimiter; default , ; \\n \\t allowed"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.push",
            name = "Array Push",
            description = "Insert a value into an array",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
                ActionField("value", "Value", hint = "value to insert"),
                ActionField("position", "Position", FieldType.NUMBER, hint = "1-based; empty = end"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.pop",
            name = "Array Pop",
            description = "Remove an element from an array into a variable",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
                ActionField("position", "Position", FieldType.NUMBER, hint = "1-based; empty = last"),
                ActionField("store", "Store removed in", hint = "variable for the popped value (optional)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.clear",
            name = "Array Clear",
            description = "Empty an array",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.process",
            name = "Array Process",
            description = "Sort, reverse, shuffle, dedupe or squash an array",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Array name", required = true, hint = "bare name, no %"),
                ActionField("type", "Type", FieldType.DROPDOWN, required = true,
                    hint = "sort / sort-desc / numeric / reverse / shuffle / unique / squash"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "array.merge",
            name = "Arrays Merge",
            description = "Concatenate several arrays into one",
            category = "Variable",
            fields = listOf(
                ActionField("arrays", "Source arrays", required = true, hint = "comma-separated array names"),
                ActionField("into", "Result array", required = true, hint = "bare name for the merged array"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.move",
            name = "Move File",
            description = "Move or rename a file within the app's files",
            category = "File",
            fields = listOf(
                ActionField("from", "From", required = true, hint = "source path"),
                ActionField("to", "To", required = true, hint = "destination path"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.mkdir",
            name = "Create Directory",
            description = "Create a directory (and parents) within the app's files",
            category = "File",
            fields = listOf(
                ActionField("path", "Path", required = true, hint = "directory path"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flash",
            name = "Flash",
            description = "Show a brief toast message",
            category = "System",
            fields = listOf(
                ActionField("text", "Text", required = true, hint = "message; supports %expansion"),
                ActionField("long", "Long", FieldType.CHECKBOX, hint = "longer display time"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.comment",
            name = "Comment",
            description = "A no-op note for documenting a task",
            category = "Flow",
            fields = listOf(
                ActionField("text", "Comment", FieldType.MULTILINE, hint = "note text"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "clipboard.set",
            name = "Set Clipboard",
            description = "Put text on the system clipboard",
            category = "System",
            fields = listOf(
                ActionField("text", "Text", FieldType.MULTILINE, hint = "text to copy"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "clipboard.get",
            name = "Get Clipboard",
            description = "Read clipboard text into a variable",
            category = "System",
            fields = listOf(
                ActionField("store", "Store in", required = true, hint = "variable name"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "email.compose",
            name = "Compose Email",
            description = "Open the email composer prefilled",
            category = "App",
            fields = listOf(
                ActionField("to", "To", hint = "comma-separated addresses"),
                ActionField("cc", "Cc", hint = "comma-separated addresses"),
                ActionField("subject", "Subject"),
                ActionField("body", "Body", FieldType.MULTILINE),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wallpaper.set",
            name = "Set Wallpaper",
            description = "Set the wallpaper from an image in the app's files",
            category = "System",
            fields = listOf(
                ActionField("path", "Image path", required = true, hint = "path to a .png/.jpg in app files"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wifi.settings",
            name = "WiFi Settings",
            description = "Open the system Wi-Fi settings screen",
            category = "Settings",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "apps.list",
            name = "List Apps",
            description = "List installed apps into array variable(s)",
            category = "App",
            fields = listOf(
                ActionField("packages", "Packages array", required = true, hint = "array name for package names"),
                ActionField("labels", "Labels array", hint = "array name for app labels (optional)"),
                ActionField("include_system", "Include system apps", FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ime.pick",
            name = "Keyboard Picker",
            description = "Show the input-method (keyboard) picker",
            category = "System",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "nav.back",
            name = "Back",
            description = "Press Back (needs the accessibility service enabled)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "nav.recents",
            name = "Recents",
            description = "Open the recent-apps overview (needs the accessibility service)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "panel.notifications",
            name = "Notifications Panel",
            description = "Open the notification shade (needs the accessibility service)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "panel.quicksettings",
            name = "Quick Settings",
            description = "Open the quick-settings panel (needs the accessibility service)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "nav.power",
            name = "Power Dialog",
            description = "Show the power / long-press menu (needs the accessibility service)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.lock",
            name = "Lock Screen",
            description = "Lock the screen — Android 9+ (needs the accessibility service)",
            category = "Interface",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "call.place",
            name = "Call",
            description = "Place a phone call (or open the dialer without CALL_PHONE)",
            category = "App",
            fields = listOf(
                ActionField("number", "Number", required = true, hint = "phone number"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "brightness.auto",
            name = "Auto Brightness",
            description = "Turn automatic screen brightness on/off (Write Settings access)",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, hint = "on / off / toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.open",
            name = "Open File",
            description = "Open a file in the app's files with another app",
            category = "File",
            fields = listOf(
                ActionField("path", "Path", required = true, hint = "file path"),
                ActionField("mime", "MIME type", hint = "optional; guessed from extension if blank"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "profile.toggle",
            name = "Profile Status",
            description = "Enable, disable, or toggle a profile by name",
            category = "System",
            fields = listOf(
                ActionField("profile", "Profile name", required = true, hint = "exact profile name"),
                ActionField("state", "State", FieldType.DROPDOWN, hint = "on / off / toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "setting.get",
            name = "Get Setting",
            description = "Read a System/Secure/Global setting into a variable",
            category = "Settings",
            fields = listOf(
                ActionField("namespace", "Namespace", FieldType.DROPDOWN, hint = "system / secure / global"),
                ActionField("name", "Setting name", required = true, hint = "e.g. screen_off_timeout"),
                ActionField("store", "Store in", required = true, hint = "variable name"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "setting.put",
            name = "Set Setting",
            description = "Write a System setting (Write Settings access; System namespace only)",
            category = "Settings",
            fields = listOf(
                ActionField("namespace", "Namespace", FieldType.DROPDOWN, hint = "system (secure/global need Shizuku)"),
                ActionField("name", "Setting name", required = true, hint = "e.g. screen_off_timeout"),
                ActionField("value", "Value", hint = "value to write"),
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
            id = "task.run",
            name = "Run Task",
            description = "Run another task as a sub-task with named parameters (it reads them as {{ param.name }}). Capture its named results and ok/error status into variables under a prefix. Globals are shared; locals are isolated; max 8 levels deep.",
            category = "Flow",
            fields = listOf(
                ActionField("task", "Task id or name", required = true, hint = "Toggle WiFi"),
                ActionField("results_prefix", "Store results into (prefix)", hint = "e.g. r_  →  %r_<name>, %r_ok, %r_error"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.return",
            name = "Return Values",
            description = "Return named values to the task that called this one (via its Run Task results prefix). Values may reference this task's variables and {{ param.* }}.",
            category = "Flow",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.fail",
            name = "Fail",
            description = "Stop the task with an error message. Surfaced to a caller's Run Task as %<prefix>error / %<prefix>ok=false.",
            category = "Flow",
            fields = listOf(
                ActionField("message", "Error message", FieldType.MULTILINE, hint = "Why the task failed"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.if",
            name = "If",
            description = "Run the following actions only when the condition is true (close with End If)",
            category = "Flow",
            fields = listOf(
                ActionField("condition", "Condition", required = true, hint = "%battery < 20"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.else",
            name = "Else",
            description = "Alternate branch executed when the matching If was false",
            category = "Flow",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endif",
            name = "End If",
            description = "Closes the matching If/Else block",
            category = "Flow",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.foreach",
            name = "For Each",
            description = "Iterate the following actions over an array variable (close with End For)",
            category = "Flow",
            fields = listOf(
                ActionField("list", "Array variable name", required = true, hint = "myList"),
                ActionField("var", "Item variable name", hint = "item"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endfor",
            name = "End For",
            description = "Closes the matching For Each loop",
            category = "Flow",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.stop",
            name = "Stop",
            description = "Halt the rest of the task immediately",
            category = "Flow",
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
            id = "intent.send",
            name = "Send Intent",
            description = "Fire an arbitrary Android intent (action, component, data URI, MIME, string extras) as an activity, service, or broadcast — e.g. the 白い熊 GNU Jami automation intents",
            category = "App",
            fields = listOf(
                ActionField("action", "Intent action", hint = "shiroikuma.jami.action.SEND_MESSAGE or android.intent.action.VIEW"),
                ActionField("package", "Target package", hint = "shiroikuma.jami"),
                ActionField("class", "Component class (fully-qualified)", hint = "cx.ring.automation.AutomationActivity"),
                ActionField("data", "Data URI", hint = "jami-cmd://send/default/<hex>?text=hi&token=…"),
                ActionField("mime", "MIME type", hint = "text/plain (optional)"),
                ActionField("target", "Dispatch target", FieldType.DROPDOWN, hint = "activity / foreground-service / service / broadcast"),
                ActionField("extra1_key", "Extra 1 key", hint = "account"),
                ActionField("extra1_value", "Extra 1 value", hint = "default"),
                ActionField("extra2_key", "Extra 2 key", hint = "peer"),
                ActionField("extra2_value", "Extra 2 value", hint = "jami:<40-hex>"),
                ActionField("extra3_key", "Extra 3 key", hint = "text"),
                ActionField("extra3_value", "Extra 3 value", hint = "Hello from a task"),
                ActionField("extra4_key", "Extra 4 key", hint = "token"),
                ActionField("extra4_value", "Extra 4 value", hint = "automation token"),
                ActionField("extra5_key", "Extra 5 key"),
                ActionField("extra5_value", "Extra 5 value"),
                ActionField("extra6_key", "Extra 6 key"),
                ActionField("extra6_value", "Extra 6 value"),
                ActionField("flags", "Intent flags", hint = "optional; decimal or 0x-hex, OR'd in"),
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
            id = "plugin.locale.query",
            name = "Locale Plugin Condition",
            description = "Query an explicit Locale/Tasker-compatible condition plugin and store its state",
            category = "Plugin",
            fields = listOf(
                ActionField("package", "Plugin package", required = true, hint = "com.example.plugin"),
                ActionField("bundleJson", "Bundle JSON", FieldType.MULTILINE, hint = "{\"key\":\"value\"}"),
                ActionField("blurb", "Blurb", hint = "Short user-visible summary"),
                ActionField("timeoutMs", "Timeout ms", FieldType.NUMBER, hint = "5000"),
                ActionField("resultVariable", "Result variable", hint = "%plugin_state"),
                ActionField("requireSatisfied", "Fail unless satisfied", FieldType.CHECKBOX),
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
                ActionField("stream", "Stream", FieldType.DROPDOWN, required = true, hint = "music / ring / alarm / notification / call / system"),
                ActionField("level", "Level", FieldType.NUMBER, required = true, hint = "0..max for the stream, or mute / unmute"),
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

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dnd.set",
            name = "Set Do Not Disturb",
            description = "Set DND interruption filter mode",
            category = "Settings",
            fields = listOf(
                ActionField("mode", "Mode", FieldType.DROPDOWN, required = true, hint = "off/priority/alarms/total_silence"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ringer.set",
            name = "Set Ringer Mode",
            description = "Set device ringer mode",
            category = "Settings",
            fields = listOf(
                ActionField("mode", "Mode", FieldType.DROPDOWN, required = true, hint = "normal/vibrate/silent"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "torch.set",
            name = "Toggle Torch",
            description = "Turn camera flashlight on or off",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tile.set",
            name = "Set Tile State",
            description = "Update Quick Settings tile state",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "active/inactive"),
                ActionField("label", "Label", required = false, hint = "Tile label text"),
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
                ActionField("allow_http", "Allow HTTP", FieldType.CHECKBOX, hint = "Allow plain HTTP for LAN/private-network hosts only"),
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
                ActionField("allow_http", "Allow HTTP", FieldType.CHECKBOX, hint = "Allow plain HTTP for LAN/private-network hosts only"),
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
                ActionField("allow_http", "Allow HTTP", FieldType.CHECKBOX, hint = "Allow plain HTTP for LAN/private-network hosts only"),
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
