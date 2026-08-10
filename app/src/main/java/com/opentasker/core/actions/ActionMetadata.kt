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
    // For DROPDOWN: the selectable values. The field stays free-text (so it can be a %variable); these
    // just populate the picker. Empty = a plain text field with no picker.
    val options: List<String> = emptyList(),
    // When true, a TEXT field also shows a folder icon that opens the system directory/file picker and
    // fills the field with the chosen filesystem path — so paths don't have to be typed by hand.
    val pathPicker: Boolean = false,
    /**
     * Explicit display sensitivity, from upstream's argument-redaction work. `null` defers to the name
     * heuristic in [ActionArgumentSensitivity] so unknown keys still fail closed; `false` marks a
     * structurally useful field the heuristic would over-mask (a variable name, a file path). Adopted
     * WITHOUT upstream's @StringRes conversion — the fork keeps its inline copy.
     */
    val sensitive: Boolean? = null,
)

enum class FieldType {
    TEXT,           // plain text input
    NUMBER,         // numeric input
    DROPDOWN,       // select from predefined values
    CHECKBOX,       // boolean toggle
    MULTILINE,      // multi-line text area
    COLOR,          // #AARRGGBB via a 4-slider RGBA picker (blank = use default)
    WIDGET_LAYOUT,  // widget layout JSON, edited with the full visual editor (+ Tasker import)
    APP_PACKAGE,    // editable text (a package name or %var) plus an installed-apps picker button
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
        // Bridge to upstream's catalogue binding. The fork keeps its inline-string metadata rather
        // than upstream's fully @StringRes version — 2 900 lines of labels the fork writes in place,
        // several of them Japanese — so this registry stays the source of truth. Binding the same
        // object into the ActionDefinition as well keeps ActionDefinition.metadata answerable for
        // any upstream code path that reads metadata through the catalogue instead of through here.
        ActionCatalog.get(metadata.id)?.bindMetadata(metadata)
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
                ActionField("tap_task", "Tap task", hint = "Task to run when the notification body is tapped (works collapsed)"),
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
            id = "notify.dismiss",
            name = "Dismiss App Notifications",
            description = "Cancel another app's notifications by package (needs notification access)",
            category = "Notification",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app whose notifications to dismiss, or type a package / %var"),
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
                ActionField("value", "Value", required = true, hint = "Supports %expansion", pathPicker = true),
            )
        )
    )

    // ---- Upstream 0.2.75/77 additions: structured data, date-time, and text/regex packs ----

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "data.read",
            name = "Read Data",
            description = "Parse JSON, CSV or XML text into variables (with an optional path selector)",
            category = "Variable",
            fields = listOf(
                ActionField("source", "Source text", required = true, hint = "The JSON/CSV/XML text (usually a %var)"),
                ActionField("format", "Format", FieldType.DROPDOWN, hint = "auto (default), json, csv or xml", options = listOf("auto", "json", "csv", "xml")),
                ActionField("path", "Path selector", hint = "e.g. items[0].name, a CSV column, or root/item/name"),
                ActionField("var", "Result variable", hint = "Also sets %var_count for arrays"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.format",
            name = "Format Date/Time",
            description = "Format epoch milliseconds as text",
            category = "Variable",
            fields = listOf(
                ActionField("time", "Time (epoch ms)", hint = "Blank = now"),
                ActionField("format", "Pattern", hint = "e.g. yyyy-MM-dd HH:mm (blank = ISO-8601)"),
                ActionField("zone", "Time zone", hint = "e.g. Asia/Tokyo (blank = device zone)"),
                ActionField("var", "Result variable", hint = "Default: %datetime_text"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.parse",
            name = "Parse Date/Time",
            description = "Parse formatted text into epoch milliseconds",
            category = "Variable",
            fields = listOf(
                ActionField("text", "Text", required = true, hint = "The date/time string to parse"),
                ActionField("format", "Pattern", required = true, hint = "e.g. yyyy-MM-dd HH:mm"),
                ActionField("zone", "Time zone", hint = "e.g. Asia/Tokyo (blank = device zone)"),
                ActionField("var", "Result variable", hint = "Default: %datetime_ms"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.add",
            name = "Add to Date/Time",
            description = "Calendar-aware date arithmetic on epoch milliseconds",
            category = "Variable",
            fields = listOf(
                ActionField("time", "Time (epoch ms)", hint = "Blank = now"),
                ActionField("amount", "Amount", FieldType.NUMBER, required = true, hint = "May be negative"),
                ActionField("unit", "Unit", FieldType.DROPDOWN, required = true, hint = "seconds … years", options = listOf("seconds", "minutes", "hours", "days", "weeks", "months", "years")),
                ActionField("var", "Result variable", hint = "Default: %datetime_ms"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.match",
            name = "Match Text",
            description = "Match a regex against text; capture groups become an array",
            category = "Variable",
            fields = listOf(
                ActionField("source", "Source text", required = true),
                ActionField("pattern", "Regex pattern", required = true, hint = "Linear-time RE2 syntax"),
                ActionField("var", "Result variable", hint = "Matches land in %var / %var(#)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.replace",
            name = "Replace Text",
            description = "Replace regex matches in text ($1 group references supported)",
            category = "Variable",
            fields = listOf(
                ActionField("source", "Source text", required = true),
                ActionField("pattern", "Regex pattern", required = true),
                ActionField("replacement", "Replacement", hint = "$1, $2 … reference capture groups"),
                ActionField("var", "Result variable"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.split",
            name = "Split Text",
            description = "Split text into an array by a literal delimiter or a regex",
            category = "Variable",
            fields = listOf(
                ActionField("source", "Source text", required = true),
                ActionField("delimiter", "Delimiter (literal)", hint = "Used when no regex pattern is given"),
                ActionField("pattern", "Regex pattern", hint = "Overrides the literal delimiter"),
                ActionField("var", "Result array variable"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.join",
            name = "Join Text",
            description = "Join an array's elements into one string",
            category = "Variable",
            fields = listOf(
                ActionField("array", "Array variable", required = true, hint = "Name of the array to join"),
                ActionField("delimiter", "Delimiter", hint = "Placed between elements (default: comma)"),
                ActionField("var", "Result variable"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.substring",
            name = "Substring",
            description = "Extract part of a text by start/end index",
            category = "Variable",
            fields = listOf(
                ActionField("source", "Source text", required = true),
                ActionField("start", "Start index", FieldType.NUMBER, required = true, hint = "0-based; negative counts from the end"),
                ActionField("end", "End index", FieldType.NUMBER, hint = "Blank = to the end"),
                ActionField("var", "Result variable"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.request",
            name = "HTTP Request",
            description = "Full HTTP request (any method) with headers, body, timeouts and response capture",
            category = "Network",
            fields = listOf(
                ActionField("method", "Method", FieldType.DROPDOWN, hint = "Default GET", options = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                ActionField("url", "URL", required = true),
                ActionField("query", "Query parameters", FieldType.MULTILINE, hint = "name=value, one per line", sensitive = true),
                ActionField("headers", "Headers", FieldType.MULTILINE, hint = "Name: value, one per line", sensitive = true),
                ActionField("authorization", "Authorization", hint = "Value for the Authorization header", sensitive = true),
                ActionField("body", "Body", FieldType.MULTILINE, sensitive = true),
                ActionField("body_file", "Body from file", hint = "Path to a file to send as the body", sensitive = false),
                ActionField("content_type", "Content type", hint = "e.g. application/json"),
                ActionField("response_var", "Response variable", hint = "Stores the response body"),
                ActionField("status_var", "Status variable", hint = "Stores the HTTP status code"),
                ActionField("headers_var", "Headers variable", hint = "Stores the response headers", sensitive = false),
                ActionField("output_file", "Response to file", hint = "Write the response body to this path"),
                ActionField("max_response_bytes", "Max response bytes", FieldType.NUMBER),
                ActionField("redirects", "Follow redirects", hint = "true/false"),
                ActionField("allow_http", "Allow cleartext HTTP (LAN)", FieldType.CHECKBOX, hint = "Only loopback/private hosts are ever allowed"),
                ActionField("timeout_sec", "Timeout (s)", FieldType.NUMBER),
                ActionField("connect_timeout_sec", "Connect timeout (s)", FieldType.NUMBER),
                ActionField("read_timeout_sec", "Read timeout (s)", FieldType.NUMBER),
                ActionField("write_timeout_sec", "Write timeout (s)", FieldType.NUMBER),
                ActionField("call_timeout_sec", "Call timeout (s)", FieldType.NUMBER),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.editaction",
            name = "Set Action Field",
            description = "Write a value into another task's action argument (e.g. bake a picker's result into a config task's var.set value so it survives startup)",
            category = "Tasks",
            fields = listOf(
                ActionField("task", "Target task name", required = true, hint = "the task to edit"),
                ActionField("matchType", "Match action type", hint = "e.g. var.set (first match wins)"),
                ActionField("matchName", "Match name arg", hint = "the variable a var.set writes, e.g. SC_Blacklist"),
                ActionField("index", "Or action index", hint = "0-based; used instead of the matchers"),
                ActionField("key", "Field to set", hint = "arg key; default 'value'"),
                ActionField("value", "New value", required = true, hint = "%-expanded before writing"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.addaction",
            name = "Add Action",
            description = "Insert an action into another task if it isn't there yet (identity = type + name arg), optionally placed alphabetically — lets a picker grow a config task's roster without ever duplicating a line",
            category = "Tasks",
            fields = listOf(
                ActionField("task", "Target task name", required = true, hint = "the task to grow"),
                ActionField("type", "Action type", required = true, hint = "e.g. var.set"),
                ActionField("name", "Name arg", hint = "the variable a var.set writes — also the identity key"),
                ActionField("value", "Value arg", hint = "written verbatim (already expanded once)"),
                ActionField("label", "Label", hint = "label for the inserted action, written verbatim"),
                ActionField("at", "Placement", hint = "end (default) / start / sorted / 0-based index"),
                ActionField("sortPattern", "Sort pattern", hint = "at=sorted: regex over the name arg; capture group 1 = the sort key"),
                ActionField("onError", "On error", hint = "continue = the inserted action keeps going on failure"),
                ActionField("store", "Result variable", hint = "gets 'added' or 'exists'"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.exists",
            name = "Task Exists",
            description = "Store true/false for whether a task of this name exists — so a task can generate a missing sub-task instead of failing on it",
            category = "Tasks",
            fields = listOf(
                ActionField("task", "Task name", required = true),
                ActionField("project", "Project name", hint = "optional — limits the search to one project"),
                ActionField("store", "Result variable", hint = "gets 'true' or 'false'; default 'exists'"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tasks.sort",
            name = "Sort Group Tasks",
            description = "Put one task group back in alphabetical order, below the project's ungrouped tasks — for groups that grow a generated task per app",
            category = "Tasks",
            fields = listOf(
                ActionField("project", "Project name", required = true),
                ActionField("group", "Group name", required = true, hint = "a group on the Tasks tab"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.persist",
            name = "Persist Variable",
            description = "Copy a variable's current value into the global scope so it survives across task runs",
            category = "Variable",
            fields = listOf(
                ActionField("name", "Source variable", required = true, hint = "local variable name"),
                ActionField("global_name", "Global name", hint = "Auto-uppercased from source if omitted"),
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
                ActionField("shared", "In shared storage", FieldType.TEXT, hint = "true = resolve under /sdcard (your own tree, visible to a file manager) instead of the app's private files"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flash",
            name = "Flash",
            description = "Show a styled overlay message. Colours/size default to the UI Flash settings.",
            category = "System",
            fields = listOf(
                ActionField("text", "Text", FieldType.MULTILINE, required = true, hint = "message; supports %expansion"),
                ActionField("html", "Use HTML", FieldType.CHECKBOX, hint = "interpret HTML tags (<b>, <h1>, <font color>, …)"),
                ActionField("text_color", "Text color", FieldType.COLOR, hint = "blank uses the UI default"),
                ActionField("background_color", "Background color", FieldType.COLOR, hint = "blank uses the UI default"),
                ActionField("border_color", "Border color", FieldType.COLOR, hint = "blank uses the UI default"),
                ActionField("position", "Position", FieldType.DROPDOWN,
                    hint = "top-left / top / top-right / left / center / right / bottom-left / bottom / bottom-right"),
                ActionField("x", "X offset (dp)", FieldType.NUMBER, hint = "horizontal offset from the anchor"),
                ActionField("y", "Y offset (dp)", FieldType.NUMBER, hint = "vertical offset from the anchor"),
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
            description = "Set the wallpaper from an image — home screen, lock screen, or both. The lock screen needs \"where\" set: the API leaves it alone otherwise",
            category = "System",
            fields = listOf(
                ActionField("path", "Image path", required = true, hint = "a .png/.jpg — in app files, or anywhere under /sdcard with \"In shared storage\" on"),
                ActionField("where", "Which screen", FieldType.TEXT, hint = "home (default), lock, or both"),
                ActionField("shared", "In shared storage", FieldType.TEXT, hint = "true = resolve the path under /sdcard instead of the app's own files"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wallpaper.live",
            name = "Set Live Wallpaper",
            description = "Switch the live wallpaper to a component. Silent with Shizuku, which can set it outright; without Shizuku the system preview opens and you confirm with a tap",
            category = "System",
            fields = listOf(
                ActionField("package", "Package", required = true, hint = "e.g. com.screensavers_store.matrixtvlivewallpaper"),
                ActionField("class", "Service class", required = true, hint = "the WallpaperService, e.g. .MatrixWallpaper (a leading dot is expanded)"),
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
            id = "nav.screenshot",
            name = "Take Screenshot",
            description = "Take a system screenshot (saved to the gallery; needs the accessibility service, API 30+)",
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
            id = "screen.lockdown",
            name = "Lockdown",
            description = "Lock and require PIN/password (biometrics disabled) — Android 9+ (needs the accessibility service)",
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
                ActionField("state", "State", FieldType.DROPDOWN, hint = "on / off / toggle (or a %variable)", options = listOf("on", "off", "toggle")),
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
                ActionField("state", "State", FieldType.DROPDOWN, hint = "on / off / toggle (or a %variable)", options = listOf("on", "off", "toggle")),
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
            id = "dialog.input",
            name = "Input Dialog",
            description = "Prompt for text and store the result in a variable. Also writes <store>_ok — true when confirmed, false when cancelled — so an answer deliberately left EMPTY can be told apart from backing out",
            category = "Alert",
            fields = listOf(
                ActionField("title", "Title"),
                ActionField("text", "Prompt", FieldType.MULTILINE),
                ActionField("default", "Default value"),
                ActionField("input_type", "Input type", FieldType.DROPDOWN, hint = "text / number / password / email"),
                ActionField("store", "Store in", hint = "variable name (default: input)"),
                ActionField("timeout", "Close after (s)", FieldType.NUMBER, hint = "0 = wait indefinitely"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dialog.list",
            name = "List Dialog",
            description = "Show a list to pick from; store the chosen item and index",
            category = "Alert",
            fields = listOf(
                ActionField("title", "Title"),
                ActionField("items", "Items", FieldType.MULTILINE, required = true, hint = "separated list (default separator: ,)"),
                ActionField("separator", "Separator", hint = "default ,"),
                ActionField("store", "Store selection in", hint = "variable name (default: selected)"),
                ActionField("store_index", "Store index in", hint = "variable for the picked index (optional)"),
                ActionField("timeout", "Close after (s)", FieldType.NUMBER, hint = "0 = wait indefinitely"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dialog.text",
            name = "Text Dialog",
            description = "Show text with OK/Cancel; store which button was pressed",
            category = "Alert",
            fields = listOf(
                ActionField("title", "Title"),
                ActionField("text", "Text", FieldType.MULTILINE),
                ActionField("ok", "OK label", hint = "default: OK"),
                ActionField("cancel", "Cancel label", hint = "default: Cancel"),
                ActionField("store", "Store OK in", hint = "variable set to true/false (optional)"),
                ActionField("timeout", "Close after (s)", FieldType.NUMBER, hint = "0 = wait indefinitely"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "shell.run",
            name = "Run Shell",
            description = "Run a shell command with Shizuku (ADB/root) privileges",
            category = "System",
            fields = listOf(
                ActionField("command", "Command", FieldType.MULTILINE, required = true, hint = "runs via sh -c"),
                ActionField("store_stdout", "Store stdout in", hint = "variable (default: stdout)"),
                ActionField("store_stderr", "Store stderr in", hint = "variable (default: stderr)"),
                ActionField("store_exit", "Store exit code in", hint = "variable (default: exit)"),
                ActionField("ignore_exit", "Ignore exit code", FieldType.CHECKBOX, hint = "succeed even if exit code is non-zero"),
            )
        )
    )

    // --- 健康: the Hume Band's stored health history ----------------------------------------------
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "band.sync",
            name = "Sync Band",
            description = "Pull the Hume Band's stored history — heart rate, HRV, SpO2, temperature, sleep, steps — into the workspace. Connects, drains every stream and disconnects; the link is never held open",
            category = "Health",
            fields = listOf(
                ActionField("from", "Read from", FieldType.TEXT, hint = "auto (default) = last successful sync minus the overlap, or 3 days back if there has never been one. A number = that many days. Or an exact yyyy-MM-dd HH:mm:ss"),
                ActionField("streams", "Streams", FieldType.TEXT, hint = "blank = all. Comma list of: hr, hrv, spo2, temp, sleep, daily, detail"),
                ActionField("address", "Band address", FieldType.TEXT, hint = "blank = the configured one. A MAC, e.g. D5:A7:06:DC:A1:3A"),
                ActionField("prefix", "Variable prefix", FieldType.TEXT, hint = "default BAND_ — writes <prefix>Phase, Pct, Records, Inserted, Stream, Summary while it runs"),
                ActionField("timeout_sec", "Whole-session timeout (s)", FieldType.NUMBER, hint = "default 180, coerced 15..600. A stream that times out is recorded and the sync moves on"),
                ActionField("backup", "Write the JSONL archive", FieldType.CHECKBOX, hint = "default on — one line per NEW record, appended, never rewritten"),
                ActionField("store", "Store summary in", FieldType.TEXT, hint = "variable to receive the one-line result"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "band.session",
            name = "Mark Training Session",
            description = "Bookend a workout the band cannot see \u2014 strength work leaves almost no trace in a wrist sensor. Bind it to a shortcut or widget and tap at the start and end; 回復 then counts the session's real heart-rate load instead of missing it",
            category = "Health",
            fields = listOf(
                ActionField("mode", "Mode", FieldType.DROPDOWN, hint = "toggle (default) starts or ends whichever applies \u2014 the one to put on a button. pick opens the chart to draw a past session on. Or start / end / log", options = listOf("toggle", "start", "end", "log", "pick", "clear")),
                ActionField("minutes", "Minutes (log only)", FieldType.NUMBER, hint = "for mode=log: a session of this many minutes that ended just now, 5..240"),
                ActionField("label", "Label", FieldType.TEXT, hint = "optional, e.g. lifting \u2014 shown beside the session"),
                ActionField("store", "Store result in", FieldType.TEXT, hint = "variable to receive the one-line result"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ocr.models",
            name = "Set OCR Models",
            description = "Point 文字認識 at the folder holding its ONNX weight files. They are not in the APK — about 100 MB that never changes — so this is where that location is declared. Blank re-runs discovery over the usual folders",
            category = "Text",
            fields = listOf(
                ActionField("folder", "Model folder", FieldType.TEXT, hint = "blank = look in the usual places. e.g. /sdcard/〇/[227] 日本語/[227][66] 辞書/[227][66][362] 文字認識モデル"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ocr.recognize",
            name = "Recognise Text (OCR)",
            description = "Read the text in an image entirely on-device (PP-OCRv5) into a variable. The same engine as the 文字認識 share tile — share a screenshot cut-out to that, or point this at a saved image",
            category = "Text",
            fields = listOf(
                ActionField("image", "Image", FieldType.TEXT, hint = "a file path, or a content:// / file:// URI. Leave blank with \"Open the review window\" on to open 文字認識 and pick an image there"),
                ActionField("script", "Script", FieldType.TEXT, hint = "blank = jpn (Japanese + English). Also: latin (German/Czech/Polish), eslav (Russian)"),
                ActionField("var", "Output variable", FieldType.TEXT, hint = "blank = %OCR. Also sets %<var>_lines and %<var>_script"),
                ActionField("model", "Model", FieldType.TEXT, hint = "blank = whatever the UI setting says. server = the accurate 81 MB model, mobile = the fast 16 MB one (Japanese/English only)"),
                ActionField("show", "Open the review window", FieldType.TEXT, hint = "true = show the image and text for checking instead of returning the text silently"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ocr.article",
            name = "Article to HTML (OCR)",
            description = "Read a scrolling screenshot into a formatted HTML file — headings, paragraphs, bold and italic, the photographs cropped out and inlined, every block sized against the body text. Handles pages far too tall for Recognise Text, and joins several screenshots of one article into a single file",
            category = "Text",
            fields = listOf(
                ActionField("images", "Screenshots", FieldType.TEXT, hint = "one path per line (or separated by |), in reading order. A repeated passage where two screenshots overlap is dropped automatically"),
                ActionField("out", "Output folder", FieldType.TEXT, hint = "blank = /sdcard/tmp. The file is named <yyyy-MM-dd_HH-mm-ss>-<headline>.html"),
                ActionField("title", "Title", FieldType.TEXT, hint = "blank = the biggest type on the page. Also the filename"),
                ActionField("script", "Script", FieldType.TEXT, hint = "blank = jpn (Japanese + English). Also: latin (German/Czech/Polish), eslav (Russian)"),
                ActionField("model", "Model", FieldType.TEXT, hint = "blank = mobile, the fast 16 MB model — an article is dozens of recognition passes, and the text is corrected by hand afterwards. server = the accurate 81 MB one. settings = follow the app-wide toggle"),
                ActionField("figures", "Figures", FieldType.TEXT, hint = "blank = embed the photographs as data URIs, so the file stands alone. none = leave a placeholder instead"),
                ActionField("figure_width", "Figure width (px)", FieldType.NUMBER, hint = "default 1600 — wider figures are scaled down before they are inlined"),
                ActionField("figure_quality", "Figure quality", FieldType.NUMBER, hint = "default 82, coerced 40..100. JPEG quality for the inlined photographs"),
                ActionField("crop_top", "Ignore at the top (px)", FieldType.NUMBER, hint = "default 0. The status bar is detected and dropped on its own; this is for anything else"),
                ActionField("crop_bottom", "Ignore at the bottom (px)", FieldType.NUMBER, hint = "default 0 — useful for an app's own \"read next\" cards at the end of the last page"),
                ActionField("var", "Variable prefix", FieldType.TEXT, hint = "default ART — writes <prefix>_File, _Title, _Blocks, _Figures, _Chars, _Pages, _Ms, and _Phase/_Pct while it runs"),
                ActionField("show", "Open the 記事変換 window", FieldType.TEXT, hint = "true = open the window instead of converting silently: add pages with +, reorder them, pick the model, watch the three progress bars and stop it with 中止. Any screenshots above are queued in it. What it reads goes to 記事編集 to be checked, and is written only when 保存 is pressed there"),
                ActionField("edit", "Open the 記事編集 window", FieldType.TEXT, hint = "true = open the editor on its own: the article above its screenshots, tap a line on the image to put the caret in it, correct it, drop what you do not want, then 保存. Its menu opens an HTML and the images"),
                ActionField("html", "Article to edit", FieldType.TEXT, hint = "with \"Open the 記事編集 window\" on: the .html to load. Blank opens it empty, to be filled from its own menu"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "band.charts",
            name = "Show Band Charts",
            description = "Open 健康 in its own fullscreen window — the band's heart rate, HRV, SpO2, temperature and stress as smooth charts. Put a launcher shortcut on the task that runs this and the icon opens straight onto the data",
            category = "Health",
            fields = listOf(
                ActionField("metric", "Only this metric", FieldType.TEXT, hint = "blank = the dashboard. One of: hr, hrv, spo2, temp, stress, steps_min, bp, sleep, index"),
                ActionField("span_minutes", "Initial span (minutes)", FieldType.NUMBER, hint = "blank = 24 hours. e.g. 360 for six hours, 60 for one"),
                ActionField("lang", "Display language", FieldType.TEXT, hint = "en-US or ja-JP. Blank keeps whatever was set last; the default is en-US"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "location.get",
            name = "Get Location",
            description = "Put the device's current position into variables. Uses Android's own LocationManager (no Play Services); accepts a recent cached fix rather than waking GPS for a phone that has not moved",
            category = "System",
            fields = listOf(
                ActionField("prefix", "Variable prefix", FieldType.TEXT, hint = "default LOC_ — writes <prefix>Lat, Lon, Acc, AgeMs, Provider, Ok"),
                ActionField("max_age_ms", "Accept a fix this old (ms)", FieldType.NUMBER, hint = "default 120000 — a cached fix this fresh is used as-is, costing no GPS time"),
                ActionField("timeout_ms", "Wait for a fresh fix (ms)", FieldType.NUMBER, hint = "default 20000 — on timeout it falls back to the newest stale fix rather than failing"),
            ),
        ),
    )

    // --- 接続: measuring each SIM's real throughput ------------------------------------------------
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "net.speedtest",
            name = "Speed Test",
            description = "Measure download/upload throughput over a chosen transport. Pins its own network, so a mobile test runs with WiFi still connected — WiFi never has to be switched off",
            category = "Network",
            fields = listOf(
                ActionField("transport", "Transport", FieldType.DROPDOWN, required = true, options = listOf("cellular", "wifi", "auto"), hint = "cellular = the SIM carrying data; auto = current default route"),
                ActionField("direction", "Direction", FieldType.DROPDOWN, options = listOf("both", "down", "up"), hint = "default: both"),
                ActionField("seconds", "Seconds per leg", FieldType.NUMBER, hint = "default 10 — the CLOCK is the limiter, as in Ookla; the size cap is only a runaway guard"),
                ActionField("max_mb", "Max MB per leg", FieldType.NUMBER, hint = "default 4000 — a runaway guard, not a target. Set it low only if you want to cap data; a cap that binds first ends the leg mid-ramp and under-reports"),
                ActionField("streams", "Parallel streams", FieldType.NUMBER, hint = "default 8 — one TCP stream is capped by window/RTT and under-reports a fast link; several fill the pipe the way real use does"),
                ActionField("prefix", "Variable prefix", hint = "default SPD_ — live progress lands in %SPD_Cur, %SPD_Avg, %SPD_Peak, %SPD_Pct, %SPD_Phase; results in %SPD_DownAvg, %SPD_UpAvg, %SPD_DownPeak, %SPD_UpPeak, %SPD_DownMs"),
                ActionField("ramp_ms", "Ignore first (ms)", FieldType.NUMBER, hint = "default 2000 — TCP slow-start is excluded from the reported average; the un-excluded figure is kept in %SPD_DownRaw"),
                ActionField("down_url", "Download URL", hint = "default: speed.cloudflare.com (anycast — routing already picks the nearest PoP), with reachability fallbacks"),
                ActionField("up_url", "Upload URL", hint = "default: speed.cloudflare.com"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "net.speedtest.cancel",
            name = "Cancel Speed Test",
            description = "Abort a running speed test immediately. The calling task still restores WiFi and the data SIM — this only stops the transfer",
            category = "Network",
            fields = listOf()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sim.data.set",
            name = "Set Data SIM",
            description = "Point mobile data at a SIM slot (0 = SIM1, 1 = SIM2). Needs Shizuku — the switch goes through a privileged telephony call",
            category = "System",
            fields = listOf(
                ActionField("slot", "SIM slot", FieldType.DROPDOWN, required = true, options = listOf("0", "1"), hint = "0 = SIM1, 1 = SIM2 — addressed by slot because subscription ids change on every re-insertion"),
                ActionField("settle_ms", "Settle (ms)", FieldType.NUMBER, hint = "default 3000 — wait for the modem to attach before measuring, or the first samples time the handover"),
                ActionField("store_previous", "Store previous slot in", hint = "variable (default: SIM_Previous) — use it to restore the original SIM afterwards"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sim.list",
            name = "Read SIMs",
            description = "Publish the active SIM slots — carrier name, subscription id, and which slot currently carries data",
            category = "System",
            fields = listOf(
                ActionField("prefix", "Variable prefix", hint = "default SIM_ — writes %SIM_Count, %SIM_0Name, %SIM_0Sub, %SIM_1Name, %SIM_1Sub, %SIM_DataSlot"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "location.mode",
            name = "Location Mode",
            description = "Turn location services on/off (via Shizuku)",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, hint = "on / off / toggle (or a %variable)", options = listOf("on", "off", "toggle")),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ime.set",
            name = "Set Keyboard",
            description = "Switch the active input method / keyboard (via Shizuku)",
            category = "System",
            fields = listOf(
                ActionField("ime", "IME id", required = true, hint = "e.g. com.pkg/.InputMethodService"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "widget.set",
            name = "Set Widget",
            description = "Replace a styled home-screen widget's layout and re-render it",
            category = "System",
            fields = listOf(
                ActionField("widget", "Widget name", required = true, hint = "the name set when the widget was placed"),
                ActionField("template", "Template (optional)", hint = "a saved Widget Templates name; overrides the layout below"),
                ActionField("layout", "Layout", FieldType.WIDGET_LAYOUT, hint = "inline layout (used when no template is set); %vars are expanded"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "widget.refresh",
            name = "Refresh Widgets",
            description = "Re-render every placed styled widget. Template-bound widgets pull their template and re-expand %vars (the pull model) — run this once per minute instead of a Set Widget per location.",
            category = "System",
            fields = emptyList(),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "scene.show",
            name = "Show Scene",
            description = "Display a scene (from the Scenes tab). With the Display-over-other-apps permission it shows over other apps; its elements run tasks on tap and %vars in text are expanded.",
            category = "System",
            fields = listOf(
                ActionField("scene", "Scene name", required = true, hint = "the scene's name — resolves within this task's project first, so a name reused in another project still finds the right one (a numeric id also works)"),
                ActionField("keepScreenOn", "Keep screen on", FieldType.CHECKBOX, hint = "the overlay blocks the screen timeout while it is shown"),
                ActionField("position", "Position", FieldType.DROPDOWN, hint = "top / center / bottom (default center)"),
                ActionField("modal", "Modal", FieldType.CHECKBOX, hint = "block the app underneath (on) vs tap-through HUD (off)"),
                ActionField("dismissOnOutside", "Tap outside closes", FieldType.CHECKBOX, hint = "default on; off = close only via Back, a button, or timeout"),
                ActionField("timeout", "Auto-dismiss (s)", FieldType.NUMBER, hint = "seconds before it closes itself; blank = stay"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "progress.show",
            name = "Progress Panel — Show",
            description = "Raise the two-pane progress panel over everything: the run's steps on top, the current step's items below, real counters, finished work ticked and dimmed above the active line, and an optional 中止 button. Declare the steps once here; afterwards just say which is current.",
            category = "System",
            fields = listOf(
                ActionField("rows", "Steps", required = true, hint = "the outer list (e.g. the packages to back up), joined by the separator"),
                ActionField("labels", "Step labels", hint = "optional display names parallel to Steps; blank = the step itself (or the app's name when Packages is on)"),
                ActionField("separator", "Separator", hint = "how Steps / labels / items are split (default a comma)"),
                ActionField("packages", "Steps are packages", FieldType.CHECKBOX, hint = "resolve each app's name and draw its icon — frozen apps included"),
                ActionField("title", "Title", hint = "panel heading"),
                ActionField("unit", "Step counter noun", hint = "e.g. アプリ → 「アプリ 7/31」"),
                ActionField("item_unit", "Item counter noun", hint = "e.g. 項目 → 「項目 3/7」"),
                ActionField("lines", "Step lines", FieldType.NUMBER, hint = "rows visible in the top pane (default 10); the active row sits with 4 done above it"),
                ActionField("item_lines", "Item lines", FieldType.NUMBER, hint = "rows visible in the bottom pane (default 8)"),
                ActionField("cancel_var", "Cancel variable", hint = "set to 1 when 中止 is pressed — and any waiting Send Intent gives up at once. Blank = no button"),
                ActionField("cancel_label", "Cancel label", hint = "button text (default 中止)"),
                ActionField("scale", "Text scale", FieldType.NUMBER, hint = "1 = normal, 1.5 = half again — match the plan window it follows"),
                ActionField("fill", "Fill the screen", FieldType.CHECKBOX, hint = "take the whole height, dividing it between the two panes, instead of a fixed row count"),
                ActionField("single", "No item pane", FieldType.CHECKBOX, hint = "the steps have no items of their own — drop the lower pane and give the step list the whole window"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "progress.row",
            name = "Progress Panel — Step",
            description = "Mark one step of the progress panel. Activating a step scrolls the top pane to it and loads its items into the bottom pane; finishing it (done/fail/cancel/skip) captures those items into the row, which tapping the row later unfolds.",
            category = "System",
            fields = listOf(
                ActionField("index", "Step number", FieldType.NUMBER, required = true, hint = "1-based position in the list given to Show"),
                ActionField("state", "State", FieldType.DROPDOWN, hint = "active / done / fail / skip / cancel / pending", options = listOf("active", "done", "fail", "skip", "cancel", "pending")),
                ActionField("detail", "Detail", hint = "right-hand annotation — a size when it worked, a reason when it didn't"),
                ActionField("items", "Items", hint = "this step's item list, loaded into the bottom pane when the step goes active"),
                ActionField("item_labels", "Item labels", hint = "optional display names parallel to Items"),
                ActionField("parents", "Item parents", hint = "optional parent id per item — one with a parent is drawn indented under its group"),
                ActionField("only", "Keep only", hint = "optional — keep just these item keys (the selected ones); blank = all of them"),
                ActionField("separator", "Separator", hint = "how Items / labels are split (default a comma)"),
                ActionField("label", "Rename step", hint = "optional — replace the row's display name"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "progress.item",
            name = "Progress Panel — Item",
            description = "Mark one item of the current step, and/or refresh the live counter line drawn under it (「書籍 1234/8942 · 512 MB / 4.2 GB」, straight from the app's own progress broadcast). Address the item by Item id whenever the app reports one — a number cannot be trusted as a position, because an app counts whatever it is working through at the time. Activating an item marks the ones above it done.",
            category = "System",
            fields = listOf(
                ActionField("key", "Item id", hint = "the category id the app says it is on — the reliable way to address a row"),
                ActionField("index", "Item number", FieldType.NUMBER, hint = "1-based position of the item being written NOW; only used when there is no Item id"),
                ActionField("index_total", "Item number is out of", FieldType.NUMBER, hint = "the app's own total — Item number is honoured only if this equals the number of items on the pane"),
                ActionField("state", "State", FieldType.DROPDOWN, hint = "active / done / fail / skip / cancel / pending", options = listOf("active", "done", "fail", "skip", "cancel", "pending")),
                ActionField("note", "Counter line", hint = "the live numbers under the active item — real counts, never a percentage"),
                ActionField("bytes", "Bytes done", FieldType.NUMBER, hint = "second counter: bytes written so far"),
                ActionField("bytes_total", "Bytes total", FieldType.NUMBER, hint = "second counter: bytes expected in total"),
                ActionField("detail", "Detail", hint = "right-hand annotation for this item"),
                ActionField("label", "Rename item", hint = "optional — replace the item's display name"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "progress.finish",
            name = "Progress Panel — Finish",
            description = "Turn the running panel into the run's report and leave it up: the item pane folds away, the button becomes OK, and the list stays browsable — every row opening to its items, its written path, and, when it failed, the full error plus buttons to grant the app storage access or re-run just that row.",
            category = "System",
            fields = listOf(
                ActionField("summary", "Summary", hint = "appended to the live ✓/✗ counts in the header, e.g. 合計 約 122 MB"),
                ActionField("retry_task", "Retry task", hint = "task name run by a row's re-run button; {key} is replaced by that row's key (e.g. 保存 ⇨ {key}). Blank = no repair buttons"),
                ActionField("row_var", "Row number variable", hint = "set to the row's 1-based number just before a retry, so the task updates that row (e.g. BR_N)"),
                ActionField("cleanup_dir", "Backup directory", hint = "a repair first deletes that app's UNREADABLE archives here (a killed export leaves a ZIP with no end-of-archive record); readable backups are never touched", pathPicker = true),
                ActionField("ok", "Button label", hint = "default OK"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "progress.hide",
            name = "Progress Panel — Hide",
            description = "Take the progress panel down (typically right before the summary dialog).",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "scene.hide",
            name = "Hide Scene",
            description = "Dismiss any scene currently shown.",
            category = "System",
            fields = emptyList(),
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
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
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
                ActionField("package", "Target app", FieldType.APP_PACKAGE, hint = "pick an app, or type a package / %var (e.g. shiroikuma.jami)"),
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
                ActionField("result_var", "Result variable (broadcast)", hint = "stores the receiver's reply"),
                ActionField("reply_via", "Reply channel", FieldType.DROPDOWN, options = listOf("", "receiver"),
                    hint = "blank = ordered-broadcast result; 'receiver' = a private ResultReceiver callback (EMUI-proof, the target reads the \"reply_to\" extra and calls back)"),
                ActionField("result_timeout", "Result timeout (s)", FieldType.NUMBER, hint = "default 5, max 60 (receiver: default 30, max 600)"),
                ActionField("watchdog", "Give up without progress (s)", FieldType.NUMBER, hint = "receiver mode: stop waiting when the target's progress reports have not CHANGED for this long — it was killed, or it is hung and merely heart-beating. Blank = wait out the whole timeout"),
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
                ActionField("stdin", "Standard input", FieldType.MULTILINE, hint = "Optional stdin payload", sensitive = true),
                ActionField("capturePrefix", "Output variable prefix", hint = "%script"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "macrodroid.unsupported",
            name = "Unsupported MacroDroid Action",
            description = "Placeholder for a MacroDroid action that could not be safely mapped during import",
            category = "Import",
            fields = listOf(
                ActionField("macroDroidType", "MacroDroid action type", required = true),
                ActionField("summary", "Import note", FieldType.MULTILINE),
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
            id = "wifi.scan",
            name = "Scan WiFi",
            description = "List nearby access points into variables — the missing half of placing the device without Play Services",
            category = "Settings",
            fields = listOf(
                // Android rate-limits scans and getScanResults returns the last cached scan either
                // way, so the action also reports whether a fresh scan was accepted and how old the
                // newest result is. Read those before trusting the list as "now".
                ActionField("var", "Output variable prefix", hint = "%wifi — writes %wifi_count, %wifi1_ssid, %wifi1_bssid, %wifi1_level, %wifi_fresh, %wifi_age_ms"),
                ActionField("limit", "Maximum networks", FieldType.NUMBER, hint = "1–64; default 20"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "aod.set",
            name = "Always-on display",
            description = "Turn the always-on display on or off — writes the secure setting through Shizuku and reads it back, so a build that accepts and ignores the write fails instead of reporting success",
            category = "Settings",
            fields = listOf(
                ActionField("state", "State", FieldType.DROPDOWN, required = true, hint = "on/off/toggle"),
            )
        )
    )

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
                ActionField("brightness", "Brightness (0-255)", FieldType.NUMBER, required = true),
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
            id = "volume.get",
            name = "Get Volume",
            description = "Read the current volume of a stream into a variable",
            category = "Settings",
            fields = listOf(
                ActionField("stream", "Stream", FieldType.DROPDOWN, required = true, hint = "music / ring / alarm / notification / call / system"),
                ActionField("var", "Store in variable", required = true, hint = "Variable name (e.g. VOL) to receive 0..max"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "state.get",
            name = "Get Device State",
            description = "Read battery %, charging, WiFi and airplane state into variables (no permissions needed)",
            category = "Settings",
            fields = listOf(
                ActionField("battery", "Battery % → variable", hint = "var for 00..99 or 100 (e.g. BATT)"),
                ActionField("charging", "Charging → variable", hint = "var for true/false (e.g. CHG)"),
                ActionField("wifi", "WiFi on → variable", hint = "var for true/false (e.g. WIFI)"),
                ActionField("airplane", "Airplane on → variable", hint = "var for true/false (e.g. AIR)"),
                ActionField("screen", "Screen on → variable", hint = "var for on/off (e.g. SCR)"),
                ActionField("app", "Foreground app → variable", hint = "var for the package name (e.g. APP)"),
                ActionField("ringer", "Ringer mode → variable", hint = "var for normal/vibrate/silent (e.g. RNG)"),
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
            id = "system.get_locale",
            name = "Get Locale",
            description = "Store the current system locale into a variable (BCP-47 tag, e.g. ja-CZ)",
            category = "Settings",
            fields = listOf(
                ActionField("var", "Store tag into", required = true, hint = "e.g. cur → %cur = ja-CZ"),
                ActionField("language_var", "Store language into", hint = "e.g. cur_lang → %cur_lang = ja"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "system.set_locale",
            name = "Set Locale",
            description = "Change the system locale persistently (no root). Needs two one-time adb grants: " +
                "pm grant shiroikuma.jiyusagyoban android.permission.CHANGE_CONFIGURATION + " +
                "appops set shiroikuma.jiyusagyoban WRITE_SETTINGS allow",
            category = "Settings",
            fields = listOf(
                ActionField("locale", "Locale", required = true,
                    hint = "en-CZ — or ja-CZ,en-CZ to toggle (sets the one not current)"),
                ActionField("result_var", "Store set tag into", hint = "variable receiving the tag actually set"),
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
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
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
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.freeze",
            name = "Freeze App",
            description = "Disable an app so it can't run (Shizuku) — it disappears until unfrozen",
            category = "App",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.unfreeze",
            name = "Unfreeze App",
            description = "Re-enable a frozen app (Shizuku)",
            category = "App",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.frozen",
            name = "Is App Frozen?",
            description = "Store true/false — whether an app is currently frozen (disabled). A frozen app cannot receive broadcasts, so check this before talking to one, then unfreeze, do the work, and re-freeze only what was frozen.",
            category = "App",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
                ActionField("store", "Store in", hint = "variable for true/false (default %frozen); an app that isn't installed stores empty"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "backup.categories",
            name = "Parse Backup Categories",
            description = "Split a sister app's LIST_CATEGORIES reply (id⇥label⇥parent⇥on/off per line) into <store>_ids / _labels / _parents / _defaults — the last being what the item picker pre-ticks.",
            category = "App",
            fields = listOf(
                ActionField("text", "Reply text", FieldType.MULTILINE, required = true, hint = "the payload with OK: already stripped"),
                ActionField("store", "Store prefix", hint = "default cat → %cat_ids, %cat_labels, %cat_parents, %cat_defaults"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "backup.plan",
            name = "Backup Plan",
            description = "Open a backup run as a plan instead of starting it: every app a ticked row that unfolds to its own items, all deselectable, with select/deselect-all at the top and inside each app. The button publishes the choice (%BR_RunApps / %BR_Run_<App>) and runs the task that performs it — the saved per-app selections are left untouched.",
            category = "App",
            fields = listOf(
                ActionField("apps", "Apps", required = true, hint = "the roster, split by Separator"),
                ActionField("separator", "Separator", hint = "default a space"),
                ActionField("confirm_task", "Run task", required = true, hint = "the task the button runs, e.g. 保存実行"),
                ActionField("confirm", "Button label", hint = "default 保存開始"),
                ActionField("title", "Title", hint = "default 保存"),
                ActionField("preselect", "Apps ticked", hint = "saved (default) = every app ticked · none = nothing ticked, pick one or two"),
                ActionField("dir", "Destination", hint = "shown as a tappable pill above the list, e.g. %BR_Dir; blank = no pill"),
                ActionField("dir_var", "Destination variable", hint = "where a folder chosen from the pill is written — this run only, never over the setting (default BR_RunDir)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "backup.edititems",
            name = "Backup Items — Edit All",
            description = "The whole roster as one item editor: every app unfolds to the items it last reported, ticked as its saved selection has them. The button writes each ticked app's choice back into %BR_Items_<App> AND into the settings task, so it becomes the default every later backup starts from. Unticking an app leaves its saved selection alone.",
            category = "App",
            fields = listOf(
                ActionField("apps", "Apps", required = true, hint = "the roster, split by Separator"),
                ActionField("separator", "Separator", hint = "default a space"),
                ActionField("settings_task", "Settings task", required = true, hint = "the 01 task the choices are baked into, e.g. 保存復元の設定 -- [979][01]"),
                ActionField("confirm", "Button label", hint = "default 保存"),
                ActionField("title", "Title", hint = "default 保存項目"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "backup.runitems",
            name = "Backup Items For Run",
            description = "Store what an app should export now: the plan's per-run choice if there is one, else its saved selection, else empty (= the app's own defaults).",
            category = "App",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var"),
                ActionField("store", "Store in", hint = "default %items_eff"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "backup.prune",
            name = "Prune Backups",
            description = "Open the backup directory as a tick-list: one row per app with its archives newest-first, everything but the newest pre-ticked, live totals, and a delete button. Nothing is deleted until you press it.",
            category = "File",
            fields = listOf(
                ActionField("dir", "Backup directory", required = true, hint = "where the .zip archives live", pathPicker = true),
                ActionField("apps", "Apps", required = true, hint = "the roster, split by Separator — only these apps are listed"),
                ActionField("separator", "Separator", hint = "how Apps is split (default a space)"),
                ActionField("keep", "Keep newest", FieldType.NUMBER, hint = "how many newest archives per app start UNticked (default 1)"),
                ActionField("title", "Title", hint = "panel heading (default 保存の整理)"),
                ActionField("lines", "Visible rows", FieldType.NUMBER, hint = "default 14"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "share.relays",
            name = "Share Apps (relay generator)",
            description = "Open the Share apps screen: pick an app, edit its name + icon, and generate a per-app share-sheet tile (a tiny relay APK) that unfreezes the app and forwards the shared content to it.",
            category = "App",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bubble.flash_add",
            name = "Flash Bubble Add",
            description = "Show a flash bubble for an app down the Desktop's LEFT edge (通知明滅) — new apps stack below existing ones and push the kill-all icon to the bottom. Tap / long-tap behavior is set in UI customization → Flash bubbles.",
            category = "System",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var (usually %NOTIF_PACKAGE)"),
                ActionField("label", "Label", hint = "bubble label; blank = the app's launcher label"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bubble.flash_remove",
            name = "Flash Bubble Remove",
            description = "Remove one app's flash bubble (no-op if it isn't shown)",
            category = "System",
            fields = listOf(
                ActionField("package", "App", FieldType.APP_PACKAGE, required = true, hint = "pick an app, or type a package / %var (usually %APP_PACKAGE)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bubble.flash_clear",
            name = "Flash Bubbles Clear",
            description = "Remove every flash bubble AND the kill-all icon (the 無効 / full-reset path)",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bubble.flashkill_show",
            name = "Flash Kill Icon Show",
            description = "Show the kill-all-flashes icon below the flash bubbles; tapping it runs the configured kill-all task (same as tapping the flash-ongoing notification) and hides itself, keeping the app bubbles",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bubble.flashkill_hide",
            name = "Flash Kill Icon Hide",
            description = "Hide the kill-all-flashes icon (the app bubbles stay)",
            category = "System",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tasks.launchers",
            name = "Make Launcher Tasks",
            description = "Pick apps; create an unfreeze-then-launch task for each in a project group.",
            category = "App",
            fields = listOf(
                ActionField("project", "Project", required = true),
                ActionField("group", "Group", required = true),
                ActionField("suffix", "Task name suffix"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.pickmulti",
            name = "Pick Apps → Variable",
            description = "Multi-select apps (the variable's current packages pre-ticked and shown first); write the chosen packages back to the variable.",
            category = "App",
            fields = listOf(
                ActionField("variable", "Variable", required = true, hint = "read pre-selection from + write back, e.g. SC_Blacklist"),
                ActionField("title", "Title", hint = "dialog title"),
                ActionField("separator", "Separator", hint = "joins the packages (default: space)"),
                ActionField("include_self", "Include this app", FieldType.DROPDOWN, options = listOf("", "true"),
                    hint = "true = 白い熊 自由作業盤 itself appears in the grid (for backup-target lists); default hides it"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dialog.pickmulti",
            name = "Pick From List → Variable",
            description = "Multi-select arbitrary items with checkboxes (全選択 master toggle on top, sub-options indented under their parent; the variable's current values pre-ticked); write the chosen values back to the variable.",
            category = "Alert",
            fields = listOf(
                ActionField("variable", "Variable", required = true, hint = "read pre-selection from + write back, e.g. BR_Items_Jami"),
                ActionField("title", "Title", hint = "dialog title"),
                ActionField("items", "Items", required = true, hint = "separator-joined values, e.g. workspace,appearance,widgets"),
                ActionField("labels", "Labels", hint = "optional display labels, parallel to Items"),
                ActionField("parents", "Parents", hint = "optional parent ids, parallel to Items (blank = top-level); children indent + follow their parent's toggle"),
                ActionField("separator", "Separator", hint = "splits Items/Labels/Parents and joins the result (default: comma)"),
                ActionField("timeout", "Timeout (s)", FieldType.NUMBER, hint = "optional; cancel after this many seconds"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.pick",
            name = "Pick One App → Variable",
            description = "Pick a single app from the icon-tile grid (one tap chooses), optionally restricted to a given package list; the package lands in the store variable.",
            category = "App",
            fields = listOf(
                ActionField("store", "Store variable", hint = "receives the picked package (default: picked); cancel stores empty"),
                ActionField("title", "Title", hint = "dialog title"),
                ActionField("packages", "Packages", hint = "optional whitespace-joined restriction, e.g. %BR_Apps; empty = all user apps"),
                ActionField("timeout", "Timeout (s)", FieldType.NUMBER, hint = "optional; cancel after this many seconds"),
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
            id = "app.previous",
            name = "Previous App",
            description = "Switch to the most recent app before this one (alt-tab). Needs Usage access.",
            category = "App",
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.next",
            name = "Next App",
            description = "Step forward through the recent-apps cycle. Needs Usage access.",
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
                ActionField("message", "Message", FieldType.MULTILINE, required = true, sensitive = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screenshot.take",
            name = "Take Screenshot",
            description = "Capture the screen to a file via Shizuku",
            category = "App",
            fields = listOf(
                ActionField("path", "Path", hint = "optional output path (default: app external files)"),
                ActionField("store", "Store path in", hint = "variable for the saved path (default: screenshot_path)"),
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
                ActionField("var", "Store in variable", required = true, hint = "%var"),
                ActionField("shared", "In shared storage", FieldType.TEXT, hint = "true = resolve under /sdcard (your own tree, visible to a file manager) instead of the app's private files"),
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
                ActionField("text", "Content", FieldType.MULTILINE, required = true),
                ActionField("shared", "In shared storage", FieldType.TEXT, hint = "true = resolve under /sdcard (your own tree, visible to a file manager) instead of the app's private files"),
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
                ActionField("text", "Content", FieldType.MULTILINE, required = true),
                ActionField("shared", "In shared storage", FieldType.TEXT, hint = "true = resolve under /sdcard (your own tree, visible to a file manager) instead of the app's private files"),
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
                ActionField("var", "Store in variable", required = true, hint = "%var"),
                ActionField("pattern", "Filename pattern", hint = "*.txt"),
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
                ActionField("var", "Store response in", hint = "%var"),
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
                ActionField("data", "Request body", FieldType.MULTILINE, sensitive = true),
                ActionField("var", "Store response in", hint = "%var"),
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
                ActionField("timeout_sec", "Timeout (seconds)", FieldType.NUMBER, hint = "Default: 5 (1-30)"),
                ActionField("var", "Result variable", hint = "Stores true/false (default: result)"),
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
                ActionField("timeout_sec", "Timeout (seconds)", FieldType.NUMBER, hint = "Default: 30 (1-300)"),
                ActionField("max_bytes", "Size limit (bytes)", FieldType.NUMBER, hint = "Default and maximum: 52428800 (50 MB)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wol",
            name = "Wake-on-LAN",
            description = "Send a magic packet to wake a device on the local network",
            category = "Network",
            fields = listOf(
                ActionField("mac", "MAC Address", required = true, hint = "e.g. AA:BB:CC:DD:EE:FF"),
                ActionField("broadcast", "Broadcast IP", hint = "Default: 255.255.255.255"),
                ActionField("port", "Port", FieldType.NUMBER, hint = "Default: 9"),
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
                ActionField("volume", "Volume (0-100)", FieldType.NUMBER, hint = "Optional; uses current volume when empty"),
                ActionField("stream", "Stream", FieldType.DROPDOWN, hint = "media (default); notification/ring/system follow the ringer mode — vibrate/silent mutes them", options = listOf("media", "notification", "ring", "alarm", "system")),
                ActionField("wait", "Wait for playback to finish", FieldType.DROPDOWN, hint = "true (default) blocks the task until the sound ends; false plays it in the background so vibration/overlays run at the same time", options = listOf("true", "false")),
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
            id = "media.playpause",
            name = "Play/Pause",
            description = "Toggle media play/pause",
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
            fields = listOf(
                ActionField("stream", "Stream", hint = "music, ring, notification, alarm, system, call (default: music)"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "audio.record.start",
            name = "Start Recording",
            description = "Start a voice recording (AAC/m4a). No-op if already recording.",
            category = "Media",
            fields = listOf(
                ActionField("dir", "Output directory", hint = "e.g. %Pkey_Dir or /sdcard/Recordings; blank = app folder"),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "audio.record.stop",
            name = "Stop Recording",
            description = "Stop the in-progress voice recording and save it (exposes %path).",
            category = "Media",
            fields = emptyList()
        )
    )

    // System actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "vibrate",
            name = "Vibrate",
            description = "Vibrate the device — one-shot, or a message-style multi-buzz pattern",
            category = "System",
            fields = listOf(
                ActionField("millis", "Duration (ms)", FieldType.NUMBER, hint = "One-shot; ignored when a pattern is given"),
                ActionField("pattern", "Pattern (ms, comma-separated)", hint = "OFF,ON alternating, first = delay — e.g. 0,150,100,150 = buzz-pause-buzz"),
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
            id = "power.off",
            name = "Power Off",
            description = "Shut down the device (via Shizuku).",
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

    // ---- Upstream 0.2.80/0.2.81 actions ------------------------------------------------
    // Ported from upstream's @StringRes catalog into the fork's inline-label format (the fork
    // UI has no labelRes plumbing). clipboard.get/clipboard.set/ime.set are absent on purpose:
    // the fork already owns those ids, and saved tasks reference the fork's actions.

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.progress",
            name = "Progress Notification",
            description = "Post ordered progress feedback with an Android 16 fallback",
            category = "Notification",
            fields = listOf(
                ActionField("title", "Title", required = true, hint = "Notification title"),
                ActionField("text", "Text", FieldType.MULTILINE, hint = "Optional status text"),
                ActionField("progress", "Progress", FieldType.NUMBER, required = true, hint = "0 to 100"),
                ActionField("segments", "Segments", hint = "Optional comma-separated positive lengths"),
                ActionField("channel", "Channel", hint = "quiet, default, or urgent"),
                ActionField("tag", "Tag", hint = "Optional replacement tag"),
                ActionField("id", "ID", FieldType.NUMBER, hint = "Optional notification ID"),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "contacts.lookup",
            name = "Lookup Contact",
            description = "Resolve a contact name, phone number, or email into sensitive variables",
            category = "Variable",
            fields = listOf(
                ActionField("query", "Name, phone, or email", required = true, hint = "Search text; maximum 128 characters", sensitive = true),
                ActionField("mode", "Lookup mode", FieldType.DROPDOWN, hint = "Picker is the default on Android 17+; permission is for unattended runs", options = listOf("picker", "permission")),
                ActionField("var", "Output variable", hint = "default: Contact; also sets name, phone, email, arrays, and count"),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "zen.rule.set",
            name = "Set Zen rule",
            description = "Create or update an owned DND rule with Android 15 device effects",
            category = "Settings",
            fields = listOf(
                ActionField("id", "Rule ID", required = true, hint = "stable key, for example focus_evening"),
                ActionField("name", "Rule name", required = true, hint = "name shown in Android DND settings"),
                ActionField("mode", "Mode", FieldType.DROPDOWN, required = true, hint = "off/priority/alarms/total_silence", options = listOf("off", "priority", "alarms", "total_silence")),
                ActionField("enabled", "Enabled", FieldType.CHECKBOX, hint = "whether Android may activate this rule"),
                ActionField("grayscale", "Grayscale display", FieldType.CHECKBOX),
                ActionField("dim_wallpaper", "Dim wallpaper", FieldType.CHECKBOX),
                ActionField("night_mode", "Night mode", FieldType.CHECKBOX),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "zen.rule.clear",
            name = "Clear Zen rule",
            description = "Remove an OpenTasker-owned DND rule",
            category = "Settings",
            fields = listOf(
                ActionField("id", "Rule ID", required = true, hint = "the stable key used by Set Zen rule"),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.archive",
            name = "Archive App",
            description = "Archive an installed application while retaining its user data",
            category = "App",
            fields = listOf(
                ActionField("package", "Package name", FieldType.APP_PACKAGE, required = true, hint = "com.example.app"),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.unarchive",
            name = "Unarchive App",
            description = "Request restoration of an archived application",
            category = "App",
            fields = listOf(
                ActionField("package", "Package name", FieldType.APP_PACKAGE, required = true, hint = "com.example.app"),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "shortcut.publish",
            name = "Publish Shortcut",
            description = "Create a dynamic or pinned launcher shortcut for a task",
            category = "App",
            fields = listOf(
                ActionField("id", "Shortcut ID", required = true, hint = "Stable ID used to update this shortcut"),
                ActionField("task_id", "Task", FieldType.TEXT, required = true, hint = "Task to run when the shortcut is tapped"),
                ActionField("label", "Label", required = true, hint = "Label shown by the launcher"),
                ActionField(
                    "mode",
                    "Mode",
                    FieldType.DROPDOWN,
                    required = true,
                    hint = "Dynamic updates immediately; pinned asks the launcher",
                    options = listOf("dynamic", "pinned"),
                ),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "state.temporary",
            name = "Temporary State",
            description = "Apply a reversible setting and restore it later",
            category = "Settings",
            fields = listOf(
                ActionField("target_action", "Setting", FieldType.DROPDOWN, required = true, options = listOf("brightness.set", "volume.set", "ringer.set", "dnd.set")),
                ActionField("target_args", "Setting arguments (JSON)", FieldType.MULTILINE, required = true, hint = "Example: {\"brightness\":\"80\"}", sensitive = true),
                ActionField("key", "Revert key", required = true, hint = "Unique channel, e.g. quiet-hours", sensitive = false),
                ActionField("duration_sec", "Duration (seconds)", FieldType.NUMBER, required = true, hint = "1 to 604800 seconds"),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ime.info",
            name = "Get Keyboard Info",
            description = "Report current and enabled keyboards",
            category = "Settings",
            fields = listOf(
                ActionField("var", "Output variable base", hint = "Defaults to IME; writes _CURRENT, _ENABLED, and _COUNT"),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "integration.home_assistant.webhook",
            name = "Home Assistant Webhook",
            description = "Send a bounded JSON event to a Home Assistant webhook with transient retry",
            category = "Network",
            fields = listOf(
                ActionField("url", "Webhook URL", required = true, hint = "HTTPS webhook URL; the secret path is redacted from display", sensitive = true),
                ActionField("payload", "JSON payload", FieldType.MULTILINE, hint = "JSON object, maximum 16 KB; payload is redacted from display", sensitive = true),
                ActionField("message", "Home Assistant message/command", hint = "Optional Companion notification message, such as command_broadcast_intent; use with data"),
                ActionField("data", "Home Assistant data", FieldType.MULTILINE, hint = "Optional JSON object paired with message; secrets are redacted from display", sensitive = true),
                ActionField("timeout_sec", "Timeout (seconds)", FieldType.NUMBER, hint = "Default: 15 (1-30)"),
                ActionField("retries", "Retries", FieldType.NUMBER, hint = "Transient failures only; default: 2 (0-3)"),
                ActionField("backoff_ms", "Retry backoff (ms)", FieldType.NUMBER, hint = "Initial delay; exponential and capped at 10 seconds (100-5000)"),
                ActionField("allow_http", "Allow HTTP", FieldType.CHECKBOX, hint = "Only for private-LAN endpoints; ACCESS_LOCAL_NETWORK and host policy still apply"),
            ),
        ),
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "mqtt.publish",
            name = "MQTT Publish",
            description = "Publish a bounded message to an MQTT 3.1.1 broker using the platform socket/TLS stack",
            category = "Network",
            fields = listOf(
                ActionField("host", "Broker host", required = true, hint = "Hostname or IP address"),
                ActionField("port", "Broker port", FieldType.NUMBER, hint = "Default: 8883 with TLS, 1883 without TLS"),
                ActionField("tls", "Use TLS", FieldType.CHECKBOX, hint = "Enabled by default; cleartext is limited to private/local hosts"),
                ActionField("topic", "Topic", required = true, hint = "Publish topic, without + or # wildcards"),
                ActionField("payload", "Payload", FieldType.MULTILINE, hint = "UTF-8 payload, maximum 64 KB; redacted from display", sensitive = true),
                ActionField("qos", "QoS", FieldType.NUMBER, hint = "0 or 1; QoS 1 waits for PUBACK"),
                ActionField("retain", "Retain", FieldType.CHECKBOX, hint = "Ask the broker to retain the last message"),
                ActionField("username", "Username", hint = "Optional broker username"),
                ActionField("password", "Password", hint = "Optional broker password; always redacted", sensitive = true),
                ActionField("timeout_sec", "Timeout (seconds)", FieldType.NUMBER, hint = "Connect, handshake, and acknowledgement timeout (1-30)"),
            ),
        ),
    )

    // Upstream 0.2.80 structured failure recovery. TaskRunner already implements the TRY/CATCH/
    // ENDTRY frames and the bounded retry; without these entries the blocks exist in the engine
    // but cannot be added in the editor.

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.try",
            name = "Try",
            description = "Run a bounded, retryable block and optionally handle its failure with Catch",
            category = "Flow",
            fields = listOf(
                ActionField("max_attempts", "Maximum attempts", FieldType.NUMBER, hint = "1–5; retries require an idempotent action"),
                ActionField("backoff_ms", "Base backoff (ms)", FieldType.NUMBER, hint = "0–60000; exponential between retries"),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.catch",
            name = "Catch",
            description = "Handle a failed Try block using FLOW_ERROR_* variables",
            category = "Flow",
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endtry",
            name = "End Try",
            description = "Closes the matching Try/Catch block",
            category = "Flow",
        )
    )
}
