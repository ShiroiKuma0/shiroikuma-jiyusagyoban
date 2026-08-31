package com.opentasker.core

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRegistriesTest {
    // Actions handled directly by the TaskRunner (not via ActionRegistry).
    private val engineHandledActions = setOf(SUB_TASK_ACTION_ID) + FlowControl.ALL

    @Test
    fun everyUiMetadataActionHasRuntimeImplementation() {
        registerActionMetadata()
        registerCoreRuntime()

        val missing = ActionMetadataRegistry.all()
            .map { it.id }
            .filter { it !in engineHandledActions && ActionRegistry.get(it) == null }

        assertTrue("Missing runtime actions: $missing", missing.isEmpty())
    }

    @Test
    fun everyRuntimeActionHasUiMetadata() {
        registerActionMetadata()
        registerCoreRuntime()

        val metadataIds = ActionMetadataRegistry.all().map { it.id }.toSet()
        val runtimeIds = ActionRegistry.allIds()
        val missing = runtimeIds.filter { it !in metadataIds }

        assertTrue("Runtime actions missing metadata: $missing", missing.isEmpty())
    }

    @Test
    fun dynamicFormMetadataUsesRuntimeArgumentKeys() {
        registerActionMetadata()

        assertFieldKeys("brightness.set", "brightness")
        assertFieldKeys("screenshot.take", "path", "store")
        assertFieldKeys("file.read", "path", "var", "shared")
        assertFieldKeys("file.write", "path", "text", "shared")
        assertFieldKeys("file.append", "path", "text", "shared")
        assertFieldKeys("file.list", "path", "var", "pattern")
        assertFieldKeys(
            "http.request",
            "method", "url", "query", "headers", "authorization", "body", "body_file", "content_type",
            "response_var", "status_var", "headers_var", "output_file", "max_response_bytes", "redirects",
            "network", "allow_http", "timeout_sec", "connect_timeout_sec", "read_timeout_sec", "write_timeout_sec",
            "call_timeout_sec",
        )
        assertFieldKeys("http.get", "url", "var", "allow_http")
        assertFieldKeys("http.post", "url", "data", "var", "allow_http")
    }

    /**
     * Every arg `ShowSceneAction` / `HideSceneAction` read had to be hand-written into a bundle's JSON
     * because no field declared it — and, worse, the editor rebuilds args from its fields, so opening
     * such an action and saving dropped them (a `scene.hide` without `scene` dismisses EVERY scene).
     * Keep the forms and the runtime argument lists in step.
     */
    @Test
    fun sceneFormsExposeEveryRuntimeArgument() {
        registerActionMetadata()

        assertFieldKeys(
            "scene.show",
            "scene", "keepScreenOn", "position", "modal", "dismissOnOutside", "timeout",
            "inset", "vAlign", "heightFraction", "widthFraction", "hAlign",
            "fullWidth", "fullscreen", "edgeCenter", "showWhenLocked",
        )
        assertFieldKeys("scene.hide", "scene")
    }

    /**
     * The remaining arguments a form never declared, each found by auditing what the runtime actually
     * reads: a volume in percent rather than raw steps, the progress row's fold-out note, a typed
     * intent extra per slot (Poweramp's `rating` wants an int), and the auto-dismiss on the two
     * dialogs that had no way to set one. Pinned so the forms don't drift from the runtime again.
     */
    @Test
    fun formsDeclareTheArgumentsTheirActionsRead() {
        registerActionMetadata()

        assertFieldKeys("volume.set", "stream", "level", "percent")
        assertFieldKeys("volume.get", "stream", "var", "percent")
        assertFieldKeys(
            "progress.row",
            "index", "state", "detail", "items", "item_labels", "parents", "only", "separator",
            "label", "note",
        )
        assertFieldKeys("app.pickmulti", "variable", "title", "separator", "include_self", "timeout")
        assertFieldKeys("tasks.launchers", "project", "group", "suffix", "timeout")

        // Every extra slot can be typed, not just the first — an inconsistent form is its own trap.
        val intentFields = ActionMetadataRegistry.get("intent.send")!!.fields.map { it.key }
        (1..6).forEach { slot ->
            assertTrue("intent.send missing extra${slot}_type", "extra${slot}_type" in intentFields)
        }
    }

    @Test
    fun coreContextSourcesIncludeLiveLocationSource() {
        registerCoreRuntime()

        val registered = ContextSourceRegistry.all().map { it.type }.toSet()

        assertTrue("Location context source must be registered: $registered", "location" in registered)
    }

    private fun assertFieldKeys(actionId: String, vararg expected: String) {
        val metadata = ActionMetadataRegistry.get(actionId)
        assertTrue("Missing metadata for $actionId", metadata != null)
        assertEquals(
            "$actionId field keys",
            expected.toList(),
            metadata!!.fields.map { it.key },
        )
    }
}
