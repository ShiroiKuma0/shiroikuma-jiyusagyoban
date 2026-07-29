package com.opentasker.core.actions

import androidx.annotation.StringRes
import com.opentasker.app.R

/**
 * Metadata describing the arguments required/optional for an Action.
 * Used to build dynamic forms in the UI.
 */
data class ActionField(
    val key: String,                    // argument key in ActionSpec.args
    @get:StringRes val labelRes: Int,   // localized UI label
    val fieldType: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    @get:StringRes val hintRes: Int? = null,
    /**
     * Explicit display sensitivity for this argument. `null` defers to the shared argument-name
     * heuristic in [ActionArgumentSensitivity], so unknown keys still fail closed; `false` marks a
     * structurally useful field the heuristic would over-mask (a variable name, a file path).
     */
    val sensitive: Boolean? = null,
)

enum class FieldType {
    TEXT,           // plain text input
    NUMBER,         // numeric input
    DROPDOWN,       // select from predefined values
    CHECKBOX,       // boolean toggle
    MULTILINE,      // multi-line text area
    TASK,           // stable task-ID picker
}

data class ActionMetadata(
    val id: String,                     // e.g. "notify.show"
    @get:StringRes val nameRes: Int,
    @get:StringRes val descriptionRes: Int,
    @get:StringRes val categoryRes: Int,
    val fields: List<ActionField> = emptyList(),
    val pickerVisible: Boolean = true,
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

    fun byCategory(@StringRes categoryRes: Int): List<ActionMetadata> =
        byId.values.filter { it.categoryRes == categoryRes }
}

// ============ Built-in Action Metadata ============

fun registerActionMetadata() {
    // Built-in actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.show",
            nameRes = R.string.catalog_action_notify_show_name,
            descriptionRes = R.string.catalog_action_notify_show_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("title", R.string.catalog_action_notify_show_field_title_label, required = true, hintRes = R.string.catalog_action_notify_show_field_title_hint),
                ActionField("text", R.string.catalog_action_notify_show_field_text_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_notify_show_field_text_hint),
                ActionField("channel", R.string.catalog_action_notify_show_field_channel_label, FieldType.DROPDOWN, hintRes = R.string.catalog_action_notify_show_field_channel_hint),
                ActionField("persistent", R.string.catalog_action_notify_show_field_persistent_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_notify_show_field_persistent_hint),
                ActionField("tag", R.string.catalog_action_notify_show_field_tag_label, hintRes = R.string.catalog_action_notify_show_field_tag_hint),
                ActionField("id", R.string.catalog_action_notify_show_field_id_label, FieldType.NUMBER, hintRes = R.string.catalog_action_notify_show_field_id_hint),
                ActionField("button1_label", R.string.catalog_action_notify_show_field_button1_label_label, hintRes = R.string.catalog_action_notify_show_field_button1_label_hint),
                ActionField("button1_task_id", R.string.catalog_action_notify_show_field_button1_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button1_task_id_hint),
                ActionField("button2_label", R.string.catalog_action_notify_show_field_button2_label_label, hintRes = R.string.catalog_action_notify_show_field_button2_label_hint),
                ActionField("button2_task_id", R.string.catalog_action_notify_show_field_button2_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button2_task_id_hint),
                ActionField("button3_label", R.string.catalog_action_notify_show_field_button3_label_label, hintRes = R.string.catalog_action_notify_show_field_button3_label_hint),
                ActionField("button3_task_id", R.string.catalog_action_notify_show_field_button3_task_id_label, FieldType.TASK, hintRes = R.string.catalog_action_notify_show_field_button3_task_id_hint),
            )
        )
    )
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "notify.cancel",
            nameRes = R.string.catalog_action_notify_cancel_name,
            descriptionRes = R.string.catalog_action_notify_cancel_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("tag", R.string.catalog_action_notify_cancel_field_tag_label, hintRes = R.string.catalog_action_notify_cancel_field_tag_hint),
                ActionField("id", R.string.catalog_action_notify_cancel_field_id_label, FieldType.NUMBER, hintRes = R.string.catalog_action_notify_cancel_field_id_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.set",
            nameRes = R.string.catalog_action_var_set_name,
            descriptionRes = R.string.catalog_action_var_set_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_set_field_name_label, required = true, hintRes = R.string.catalog_action_var_set_field_name_hint),
                ActionField("value", R.string.catalog_action_var_set_field_value_label, required = true, hintRes = R.string.catalog_action_var_set_field_value_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "var.persist",
            nameRes = R.string.catalog_action_var_persist_name,
            descriptionRes = R.string.catalog_action_var_persist_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("name", R.string.catalog_action_var_persist_field_name_label, required = true, hintRes = R.string.catalog_action_var_persist_field_name_hint),
                ActionField("global_name", R.string.catalog_action_var_persist_field_global_name_label, hintRes = R.string.catalog_action_var_persist_field_global_name_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "data.read",
            nameRes = R.string.catalog_action_data_read_name,
            descriptionRes = R.string.catalog_action_data_read_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_data_read_field_source_label, required = true, hintRes = R.string.catalog_action_data_read_field_source_hint),
                ActionField("format", R.string.catalog_action_data_read_field_format_label, hintRes = R.string.catalog_action_data_read_field_format_hint),
                ActionField("path", R.string.catalog_action_data_read_field_path_label, hintRes = R.string.catalog_action_data_read_field_path_hint),
                ActionField("var", R.string.catalog_action_data_read_field_var_label, hintRes = R.string.catalog_action_data_read_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.format",
            nameRes = R.string.catalog_action_datetime_format_name,
            descriptionRes = R.string.catalog_action_datetime_format_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("time", R.string.catalog_action_datetime_format_field_time_label, hintRes = R.string.catalog_action_datetime_format_field_time_hint),
                ActionField("format", R.string.catalog_action_datetime_format_field_format_label, hintRes = R.string.catalog_action_datetime_format_field_format_hint),
                ActionField("zone", R.string.catalog_action_datetime_format_field_zone_label, hintRes = R.string.catalog_action_datetime_format_field_zone_hint),
                ActionField("var", R.string.catalog_action_datetime_format_field_var_label, hintRes = R.string.catalog_action_datetime_format_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.parse",
            nameRes = R.string.catalog_action_datetime_parse_name,
            descriptionRes = R.string.catalog_action_datetime_parse_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("text", R.string.catalog_action_datetime_parse_field_text_label, required = true, hintRes = R.string.catalog_action_datetime_parse_field_text_hint),
                ActionField("format", R.string.catalog_action_datetime_parse_field_format_label, required = true, hintRes = R.string.catalog_action_datetime_parse_field_format_hint),
                ActionField("zone", R.string.catalog_action_datetime_parse_field_zone_label, hintRes = R.string.catalog_action_datetime_parse_field_zone_hint),
                ActionField("var", R.string.catalog_action_datetime_parse_field_var_label, hintRes = R.string.catalog_action_datetime_parse_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "datetime.add",
            nameRes = R.string.catalog_action_datetime_add_name,
            descriptionRes = R.string.catalog_action_datetime_add_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("time", R.string.catalog_action_datetime_add_field_time_label, hintRes = R.string.catalog_action_datetime_add_field_time_hint),
                ActionField("amount", R.string.catalog_action_datetime_add_field_amount_label, required = true, hintRes = R.string.catalog_action_datetime_add_field_amount_hint),
                ActionField("unit", R.string.catalog_action_datetime_add_field_unit_label, required = true, hintRes = R.string.catalog_action_datetime_add_field_unit_hint),
                ActionField("var", R.string.catalog_action_datetime_add_field_var_label, hintRes = R.string.catalog_action_datetime_add_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.match",
            nameRes = R.string.catalog_action_text_match_name,
            descriptionRes = R.string.catalog_action_text_match_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_match_field_source_label, required = true),
                ActionField("pattern", R.string.catalog_action_text_match_field_pattern_label, required = true, hintRes = R.string.catalog_action_text_match_field_pattern_hint),
                ActionField("var", R.string.catalog_action_text_match_field_var_label, hintRes = R.string.catalog_action_text_match_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.replace",
            nameRes = R.string.catalog_action_text_replace_name,
            descriptionRes = R.string.catalog_action_text_replace_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_replace_field_source_label, required = true),
                ActionField("pattern", R.string.catalog_action_text_replace_field_pattern_label, required = true),
                ActionField("replacement", R.string.catalog_action_text_replace_field_replacement_label, hintRes = R.string.catalog_action_text_replace_field_replacement_hint),
                ActionField("var", R.string.catalog_action_text_replace_field_var_label, hintRes = R.string.catalog_action_text_replace_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.split",
            nameRes = R.string.catalog_action_text_split_name,
            descriptionRes = R.string.catalog_action_text_split_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_split_field_source_label, required = true),
                ActionField("delimiter", R.string.catalog_action_text_split_field_delimiter_label, hintRes = R.string.catalog_action_text_split_field_delimiter_hint),
                ActionField("pattern", R.string.catalog_action_text_split_field_pattern_label, hintRes = R.string.catalog_action_text_split_field_pattern_hint),
                ActionField("var", R.string.catalog_action_text_split_field_var_label, hintRes = R.string.catalog_action_text_split_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.join",
            nameRes = R.string.catalog_action_text_join_name,
            descriptionRes = R.string.catalog_action_text_join_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("array", R.string.catalog_action_text_join_field_array_label, required = true, hintRes = R.string.catalog_action_text_join_field_array_hint),
                ActionField("delimiter", R.string.catalog_action_text_join_field_delimiter_label, hintRes = R.string.catalog_action_text_join_field_delimiter_hint),
                ActionField("var", R.string.catalog_action_text_join_field_var_label, hintRes = R.string.catalog_action_text_join_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "text.substring",
            nameRes = R.string.catalog_action_text_substring_name,
            descriptionRes = R.string.catalog_action_text_substring_description,
            categoryRes = R.string.catalog_category_variable,
            fields = listOf(
                ActionField("source", R.string.catalog_action_text_substring_field_source_label, required = true),
                ActionField("start", R.string.catalog_action_text_substring_field_start_label, required = true, hintRes = R.string.catalog_action_text_substring_field_start_hint),
                ActionField("end", R.string.catalog_action_text_substring_field_end_label, hintRes = R.string.catalog_action_text_substring_field_end_hint),
                ActionField("var", R.string.catalog_action_text_substring_field_var_label, hintRes = R.string.catalog_action_text_substring_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tts.speak",
            nameRes = R.string.catalog_action_tts_speak_name,
            descriptionRes = R.string.catalog_action_tts_speak_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(
                ActionField("text", R.string.catalog_action_tts_speak_field_text_label, FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_flow_wait_field_millis_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_flow_wait_field_millis_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "task.run",
            nameRes = R.string.catalog_action_task_run_name,
            descriptionRes = R.string.catalog_action_task_run_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("task", R.string.catalog_action_task_run_field_task_label, required = true, hintRes = R.string.catalog_action_task_run_field_task_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.if",
            nameRes = R.string.catalog_action_flow_if_name,
            descriptionRes = R.string.catalog_action_flow_if_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("condition", R.string.catalog_action_flow_if_field_condition_label, required = true, hintRes = R.string.catalog_action_flow_if_field_condition_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.else",
            nameRes = R.string.catalog_action_flow_else_name,
            descriptionRes = R.string.catalog_action_flow_else_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endif",
            nameRes = R.string.catalog_action_flow_endif_name,
            descriptionRes = R.string.catalog_action_flow_endif_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.foreach",
            nameRes = R.string.catalog_action_flow_foreach_name,
            descriptionRes = R.string.catalog_action_flow_foreach_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("list", R.string.catalog_action_flow_foreach_field_list_label, required = true, hintRes = R.string.catalog_action_flow_foreach_field_list_hint),
                ActionField("var", R.string.catalog_action_flow_foreach_field_var_label, hintRes = R.string.catalog_action_flow_foreach_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.endfor",
            nameRes = R.string.catalog_action_flow_endfor_name,
            descriptionRes = R.string.catalog_action_flow_endfor_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "flow.stop",
            nameRes = R.string.catalog_action_flow_stop_name,
            descriptionRes = R.string.catalog_action_flow_stop_description,
            categoryRes = R.string.catalog_category_flow,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "intent.launch",
            nameRes = R.string.catalog_action_intent_launch_name,
            descriptionRes = R.string.catalog_action_intent_launch_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_intent_launch_field_package_label, required = true, hintRes = R.string.catalog_action_intent_launch_field_package_hint),
                ActionField("action", R.string.catalog_action_intent_launch_field_action_label, hintRes = R.string.catalog_action_intent_launch_field_action_hint),
                ActionField("category", R.string.catalog_action_intent_launch_field_category_label, hintRes = R.string.catalog_action_intent_launch_field_category_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "plugin.locale.fire",
            nameRes = R.string.catalog_action_plugin_locale_fire_name,
            descriptionRes = R.string.catalog_action_plugin_locale_fire_description,
            categoryRes = R.string.catalog_category_plugin,
            fields = listOf(
                ActionField("package", R.string.catalog_action_plugin_locale_fire_field_package_label, required = true, hintRes = R.string.catalog_action_plugin_locale_fire_field_package_hint),
                ActionField("bundleJson", R.string.catalog_action_plugin_locale_fire_field_bundlejson_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_plugin_locale_fire_field_bundlejson_hint),
                ActionField("blurb", R.string.catalog_action_plugin_locale_fire_field_blurb_label, hintRes = R.string.catalog_action_plugin_locale_fire_field_blurb_hint),
                ActionField("timeoutMs", R.string.catalog_action_plugin_locale_fire_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_plugin_locale_fire_field_timeoutms_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "plugin.locale.query",
            nameRes = R.string.catalog_action_plugin_locale_query_name,
            descriptionRes = R.string.catalog_action_plugin_locale_query_description,
            categoryRes = R.string.catalog_category_plugin,
            fields = listOf(
                ActionField("package", R.string.catalog_action_plugin_locale_query_field_package_label, required = true, hintRes = R.string.catalog_action_plugin_locale_query_field_package_hint),
                ActionField("bundleJson", R.string.catalog_action_plugin_locale_query_field_bundlejson_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_plugin_locale_query_field_bundlejson_hint),
                ActionField("blurb", R.string.catalog_action_plugin_locale_query_field_blurb_label, hintRes = R.string.catalog_action_plugin_locale_query_field_blurb_hint),
                ActionField("timeoutMs", R.string.catalog_action_plugin_locale_query_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_plugin_locale_query_field_timeoutms_hint),
                ActionField("resultVariable", R.string.catalog_action_plugin_locale_query_field_resultvariable_label, hintRes = R.string.catalog_action_plugin_locale_query_field_resultvariable_hint),
                ActionField("requireSatisfied", R.string.catalog_action_plugin_locale_query_field_requiresatisfied_label, FieldType.CHECKBOX),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "script.termux.run",
            nameRes = R.string.catalog_action_script_termux_run_name,
            descriptionRes = R.string.catalog_action_script_termux_run_description,
            categoryRes = R.string.catalog_category_script,
            fields = listOf(
                ActionField("executable", R.string.catalog_action_script_termux_run_field_executable_label, required = true, hintRes = R.string.catalog_action_script_termux_run_field_executable_hint),
                ActionField("arguments", R.string.catalog_action_script_termux_run_field_arguments_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_script_termux_run_field_arguments_hint),
                ActionField("workingDirectory", R.string.catalog_action_script_termux_run_field_workingdirectory_label, hintRes = R.string.catalog_action_script_termux_run_field_workingdirectory_hint),
                ActionField("stdin", R.string.catalog_action_script_termux_run_field_stdin_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_script_termux_run_field_stdin_hint, sensitive = true),
                ActionField("capturePrefix", R.string.catalog_action_script_termux_run_field_captureprefix_label, hintRes = R.string.catalog_action_script_termux_run_field_captureprefix_hint),
                ActionField("timeoutMs", R.string.catalog_action_script_termux_run_field_timeoutms_label, FieldType.NUMBER, hintRes = R.string.catalog_action_script_termux_run_field_timeoutms_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tasker.unsupported",
            nameRes = R.string.catalog_action_tasker_unsupported_name,
            descriptionRes = R.string.catalog_action_tasker_unsupported_description,
            categoryRes = R.string.catalog_category_import,
            fields = listOf(
                ActionField("taskerCode", R.string.catalog_action_tasker_unsupported_field_taskercode_label, required = true),
                ActionField("summary", R.string.catalog_action_tasker_unsupported_field_summary_label, FieldType.MULTILINE),
            )
        )
    )

    // Settings actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wifi.toggle",
            nameRes = R.string.catalog_action_wifi_toggle_name,
            descriptionRes = R.string.catalog_action_wifi_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_wifi_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_wifi_toggle_field_state_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "bluetooth.toggle",
            nameRes = R.string.catalog_action_bluetooth_toggle_name,
            descriptionRes = R.string.catalog_action_bluetooth_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_bluetooth_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_bluetooth_toggle_field_state_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "brightness.set",
            nameRes = R.string.catalog_action_brightness_set_name,
            descriptionRes = R.string.catalog_action_brightness_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("brightness", R.string.catalog_action_brightness_set_field_brightness_label, FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "volume.set",
            nameRes = R.string.catalog_action_volume_set_name,
            descriptionRes = R.string.catalog_action_volume_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("stream", R.string.catalog_action_volume_set_field_stream_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_volume_set_field_stream_hint),
                ActionField("level", R.string.catalog_action_volume_set_field_level_label, FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "airplane.toggle",
            nameRes = R.string.catalog_action_airplane_toggle_name,
            descriptionRes = R.string.catalog_action_airplane_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_airplane_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_airplane_toggle_field_state_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "mobile.toggle",
            nameRes = R.string.catalog_action_mobile_toggle_name,
            descriptionRes = R.string.catalog_action_mobile_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_mobile_toggle_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_mobile_toggle_field_state_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.timeout",
            nameRes = R.string.catalog_action_screen_timeout_name,
            descriptionRes = R.string.catalog_action_screen_timeout_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_screen_timeout_field_millis_label, FieldType.NUMBER, required = true, hintRes = R.string.catalog_action_screen_timeout_field_millis_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "dnd.set",
            nameRes = R.string.catalog_action_dnd_set_name,
            descriptionRes = R.string.catalog_action_dnd_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("mode", R.string.catalog_action_dnd_set_field_mode_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_dnd_set_field_mode_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ringer.set",
            nameRes = R.string.catalog_action_ringer_set_name,
            descriptionRes = R.string.catalog_action_ringer_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("mode", R.string.catalog_action_ringer_set_field_mode_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_ringer_set_field_mode_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "torch.set",
            nameRes = R.string.catalog_action_torch_set_name,
            descriptionRes = R.string.catalog_action_torch_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_torch_set_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_torch_set_field_state_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "tile.set",
            nameRes = R.string.catalog_action_tile_set_name,
            descriptionRes = R.string.catalog_action_tile_set_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField("state", R.string.catalog_action_tile_set_field_state_label, FieldType.DROPDOWN, required = true, hintRes = R.string.catalog_action_tile_set_field_state_hint),
                ActionField("label", R.string.catalog_action_tile_set_field_label_label, required = false, hintRes = R.string.catalog_action_tile_set_field_label_hint),
            )
        )
    )

    // App actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.launch",
            nameRes = R.string.catalog_action_app_launch_name,
            descriptionRes = R.string.catalog_action_app_launch_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_launch_field_package_label, required = true, hintRes = R.string.catalog_action_app_launch_field_package_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "app.kill",
            nameRes = R.string.catalog_action_app_kill_name,
            descriptionRes = R.string.catalog_action_app_kill_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("package", R.string.catalog_action_app_kill_field_package_label, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "home.go",
            nameRes = R.string.catalog_action_home_go_name,
            descriptionRes = R.string.catalog_action_home_go_description,
            categoryRes = R.string.catalog_category_app,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "url.open",
            nameRes = R.string.catalog_action_url_open_name,
            descriptionRes = R.string.catalog_action_url_open_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("url", R.string.catalog_action_url_open_field_url_label, required = true, hintRes = R.string.catalog_action_url_open_field_url_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sms.send",
            nameRes = R.string.catalog_action_sms_send_name,
            descriptionRes = R.string.catalog_action_sms_send_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("number", R.string.catalog_action_sms_send_field_number_label, required = true),
                ActionField("message", R.string.catalog_action_sms_send_field_message_label, FieldType.MULTILINE, required = true, sensitive = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screenshot.take",
            nameRes = R.string.catalog_action_screenshot_take_name,
            descriptionRes = R.string.catalog_action_screenshot_take_description,
            categoryRes = R.string.catalog_category_app,
            fields = listOf(
                ActionField("path", R.string.catalog_action_screenshot_take_field_path_label, hintRes = R.string.catalog_action_screenshot_take_field_path_hint),
            )
        )
    )

    // File actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.read",
            nameRes = R.string.catalog_action_file_read_name,
            descriptionRes = R.string.catalog_action_file_read_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_read_field_path_label, required = true),
                ActionField("var", R.string.catalog_action_file_read_field_var_label, required = true, hintRes = R.string.catalog_action_file_read_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.write",
            nameRes = R.string.catalog_action_file_write_name,
            descriptionRes = R.string.catalog_action_file_write_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_write_field_path_label, required = true),
                ActionField("text", R.string.catalog_action_file_write_field_text_label, FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.append",
            nameRes = R.string.catalog_action_file_append_name,
            descriptionRes = R.string.catalog_action_file_append_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_append_field_path_label, required = true),
                ActionField("text", R.string.catalog_action_file_append_field_text_label, FieldType.MULTILINE, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.delete",
            nameRes = R.string.catalog_action_file_delete_name,
            descriptionRes = R.string.catalog_action_file_delete_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_delete_field_path_label, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "file.list",
            nameRes = R.string.catalog_action_file_list_name,
            descriptionRes = R.string.catalog_action_file_list_description,
            categoryRes = R.string.catalog_category_file,
            fields = listOf(
                ActionField("path", R.string.catalog_action_file_list_field_path_label, required = true),
                ActionField("var", R.string.catalog_action_file_list_field_var_label, required = true, hintRes = R.string.catalog_action_file_list_field_var_hint),
                ActionField("pattern", R.string.catalog_action_file_list_field_pattern_label, hintRes = R.string.catalog_action_file_list_field_pattern_hint),
            )
        )
    )

    // Network actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.request",
            nameRes = R.string.catalog_action_http_request_name,
            descriptionRes = R.string.catalog_action_http_request_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("method", R.string.catalog_action_http_request_field_method_label, hintRes = R.string.catalog_action_http_request_field_method_hint),
                ActionField("url", R.string.catalog_action_http_request_field_url_label, required = true),
                ActionField("query", R.string.catalog_action_http_request_field_query_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_http_request_field_query_hint, sensitive = true),
                ActionField("headers", R.string.catalog_action_http_request_field_headers_label, FieldType.MULTILINE, hintRes = R.string.catalog_action_http_request_field_headers_hint, sensitive = true),
                ActionField("authorization", R.string.catalog_action_http_request_field_authorization_label, hintRes = R.string.catalog_action_http_request_field_authorization_hint, sensitive = true),
                ActionField("body", R.string.catalog_action_http_request_field_body_label, FieldType.MULTILINE, sensitive = true),
                ActionField("body_file", R.string.catalog_action_http_request_field_body_file_label, hintRes = R.string.catalog_action_http_request_field_body_file_hint, sensitive = false),
                ActionField("content_type", R.string.catalog_action_http_request_field_content_type_label, hintRes = R.string.catalog_action_http_request_field_content_type_hint),
                ActionField("response_var", R.string.catalog_action_http_request_field_response_var_label, hintRes = R.string.catalog_action_http_request_field_response_var_hint),
                ActionField("status_var", R.string.catalog_action_http_request_field_status_var_label, hintRes = R.string.catalog_action_http_request_field_status_var_hint),
                ActionField("headers_var", R.string.catalog_action_http_request_field_headers_var_label, hintRes = R.string.catalog_action_http_request_field_headers_var_hint, sensitive = false),
                ActionField("output_file", R.string.catalog_action_http_request_field_output_file_label, hintRes = R.string.catalog_action_http_request_field_output_file_hint),
                ActionField("max_response_bytes", R.string.catalog_action_http_request_field_max_response_bytes_label, FieldType.NUMBER, hintRes = R.string.catalog_action_http_request_field_max_response_bytes_hint),
                ActionField("redirects", R.string.catalog_action_http_request_field_redirects_label, hintRes = R.string.catalog_action_http_request_field_redirects_hint),
                ActionField("allow_http", R.string.catalog_action_http_request_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_request_field_allow_http_hint),
                ActionField("timeout_sec", R.string.catalog_action_http_request_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_http_request_field_timeout_hint),
                ActionField("connect_timeout_sec", R.string.catalog_action_http_request_field_connect_timeout_label, FieldType.NUMBER),
                ActionField("read_timeout_sec", R.string.catalog_action_http_request_field_read_timeout_label, FieldType.NUMBER),
                ActionField("write_timeout_sec", R.string.catalog_action_http_request_field_write_timeout_label, FieldType.NUMBER),
                ActionField("call_timeout_sec", R.string.catalog_action_http_request_field_call_timeout_label, FieldType.NUMBER),
            ),
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.get",
            nameRes = R.string.catalog_action_http_get_name,
            descriptionRes = R.string.catalog_action_http_get_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_http_get_field_url_label, required = true),
                ActionField("var", R.string.catalog_action_http_get_field_var_label, hintRes = R.string.catalog_action_http_get_field_var_hint),
                ActionField("allow_http", R.string.catalog_action_http_get_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_get_field_allow_http_hint),
            ),
            pickerVisible = false,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "http.post",
            nameRes = R.string.catalog_action_http_post_name,
            descriptionRes = R.string.catalog_action_http_post_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_http_post_field_url_label, required = true),
                ActionField("data", R.string.catalog_action_http_post_field_data_label, FieldType.MULTILINE, sensitive = true),
                ActionField("var", R.string.catalog_action_http_post_field_var_label, hintRes = R.string.catalog_action_http_post_field_var_hint),
                ActionField("allow_http", R.string.catalog_action_http_post_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_http_post_field_allow_http_hint),
            ),
            pickerVisible = false,
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "ping",
            nameRes = R.string.catalog_action_ping_name,
            descriptionRes = R.string.catalog_action_ping_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("host", R.string.catalog_action_ping_field_host_label, required = true),
                ActionField("timeout_sec", R.string.catalog_action_ping_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_ping_field_timeout_hint),
                ActionField("var", R.string.catalog_action_ping_field_var_label, hintRes = R.string.catalog_action_ping_field_var_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "download",
            nameRes = R.string.catalog_action_download_name,
            descriptionRes = R.string.catalog_action_download_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("url", R.string.catalog_action_download_field_url_label, required = true),
                ActionField("path", R.string.catalog_action_download_field_path_label, required = true),
                ActionField("allow_http", R.string.catalog_action_download_field_allow_http_label, FieldType.CHECKBOX, hintRes = R.string.catalog_action_download_field_allow_http_hint),
                ActionField("timeout_sec", R.string.catalog_action_download_field_timeout_label, FieldType.NUMBER, hintRes = R.string.catalog_action_download_field_timeout_hint),
                ActionField("max_bytes", R.string.catalog_action_download_field_max_bytes_label, FieldType.NUMBER, hintRes = R.string.catalog_action_download_field_max_bytes_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wol",
            nameRes = R.string.catalog_action_wol_name,
            descriptionRes = R.string.catalog_action_wol_description,
            categoryRes = R.string.catalog_category_network,
            fields = listOf(
                ActionField("mac", R.string.catalog_action_wol_field_mac_label, required = true, hintRes = R.string.catalog_action_wol_field_mac_hint),
                ActionField("broadcast", R.string.catalog_action_wol_field_broadcast_label, hintRes = R.string.catalog_action_wol_field_broadcast_hint),
                ActionField("port", R.string.catalog_action_wol_field_port_label, FieldType.NUMBER, hintRes = R.string.catalog_action_wol_field_port_hint),
            )
        )
    )

    // Media actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.play",
            nameRes = R.string.catalog_action_sound_play_name,
            descriptionRes = R.string.catalog_action_sound_play_description,
            categoryRes = R.string.catalog_category_media,
            fields = listOf(
                ActionField("path", R.string.catalog_action_sound_play_field_path_label, required = true),
                ActionField("volume", R.string.catalog_action_sound_play_field_volume_label, FieldType.NUMBER, hintRes = R.string.catalog_action_sound_play_field_volume_hint),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.stop",
            nameRes = R.string.catalog_action_sound_stop_name,
            descriptionRes = R.string.catalog_action_sound_stop_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "sound.pause",
            nameRes = R.string.catalog_action_sound_pause_name,
            descriptionRes = R.string.catalog_action_sound_pause_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.next",
            nameRes = R.string.catalog_action_track_next_name,
            descriptionRes = R.string.catalog_action_track_next_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "track.previous",
            nameRes = R.string.catalog_action_track_previous_name,
            descriptionRes = R.string.catalog_action_track_previous_description,
            categoryRes = R.string.catalog_category_media,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "media.mute",
            nameRes = R.string.catalog_action_media_mute_name,
            descriptionRes = R.string.catalog_action_media_mute_description,
            categoryRes = R.string.catalog_category_media,
            fields = listOf(
                ActionField("stream", R.string.catalog_action_media_mute_field_stream_label, hintRes = R.string.catalog_action_media_mute_field_stream_hint),
            )
        )
    )

    // System actions
    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "vibrate",
            nameRes = R.string.catalog_action_vibrate_name,
            descriptionRes = R.string.catalog_action_vibrate_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_vibrate_field_millis_label, FieldType.NUMBER, required = true),
            )
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "reboot",
            nameRes = R.string.catalog_action_reboot_name,
            descriptionRes = R.string.catalog_action_reboot_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "lock",
            nameRes = R.string.catalog_action_lock_name,
            descriptionRes = R.string.catalog_action_lock_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "screen.off",
            nameRes = R.string.catalog_action_screen_off_name,
            descriptionRes = R.string.catalog_action_screen_off_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "wake",
            nameRes = R.string.catalog_action_wake_name,
            descriptionRes = R.string.catalog_action_wake_description,
            categoryRes = R.string.catalog_category_system,
            fields = emptyList()
        )
    )

    ActionMetadataRegistry.register(
        ActionMetadata(
            id = "log",
            nameRes = R.string.catalog_action_log_name,
            descriptionRes = R.string.catalog_action_log_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(
                ActionField("message", R.string.catalog_action_log_field_message_label, FieldType.MULTILINE, required = true),
            )
        )
    )
}
