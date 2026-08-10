package com.opentasker.core.plugins.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.AutomationLiveConditionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Locale/Tasker condition-plugin endpoint for OpenTasker's own condition selections. */
class LocaleConditionQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalePluginContract.ACTION_QUERY_CONDITION) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = withTimeoutOrNull(MAX_QUERY_MS) {
                    runCatching {
                        evaluate(context.applicationContext, intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE))
                    }.getOrDefault(LocalePluginConditionState.Unknown)
                } ?: LocalePluginConditionState.Unknown
                pendingResult.setResultCode(state.resultCode)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun evaluate(context: Context, bundle: android.os.Bundle?): LocalePluginConditionState {
        val values = runCatching {
            requireNotNull(bundle) { "Missing Locale condition bundle." }
            val sanitized = LocalePluginBundleCodec.fromBundle(bundle)
            val encoded = LocalePluginBundleCodec.encodeStringBundle(sanitized)
            require(encoded.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
                "Locale condition bundle is too large."
            }
            sanitized
        }.getOrNull() ?: return LocalePluginConditionState.Unknown
        val spec = runCatching { LocaleConditionTarget.parse(values) }.getOrNull()
            ?: return LocalePluginConditionState.Unknown
        val db = runCatching { OpenTaskerApp_NoHilt.db }.getOrNull()
            ?: return LocalePluginConditionState.Unknown

        return when (spec.kind) {
            LocaleConditionKind.PROFILE_ACTIVE,
            LocaleConditionKind.CONTEXT_SATISFIED,
            -> {
                val profileId = spec.profileId ?: return LocalePluginConditionState.Unknown
                val entity = db.profileDao().getById(profileId) ?: return LocalePluginConditionState.Unknown
                val profile = entity.toDomainDecodeResult()
                val contextMatched = if (spec.kind == LocaleConditionKind.CONTEXT_SATISFIED) {
                    val index = spec.contextIndex ?: return LocalePluginConditionState.Unknown
                    if (profile.issue != null || index !in profile.value.contexts.indices) {
                        return LocalePluginConditionState.Unknown
                    }
                    AutomationLiveConditionState.contextState(profileId, index)
                } else {
                    null
                }
                LocaleConditionEvaluator.evaluate(
                    spec,
                    LocaleConditionSnapshot(
                        profileExists = true,
                        profileEnabled = entity.enabled && !entity.requiresRiskAcknowledgement,
                        profileActive = AutomationLiveConditionState.profileState(profileId),
                        contextMatched = contextMatched,
                    ),
                )
            }
            LocaleConditionKind.VARIABLE_COMPARE -> {
                val variableName = spec.variableName ?: return LocalePluginConditionState.Unknown
                // The receiver is exported without a permission, so the only thing separating a
                // legitimate host from an app probing variables one comparison at a time is the
                // grant the user minted when they chose this variable.
                val authorized = LocaleConditionGrantStore(context).isValid(
                    spec.grantToken,
                    LocaleConditionGrantStore.variableKey(spec.variableProjectId, variableName),
                )
                if (!authorized) return LocalePluginConditionState.Unknown
                val entity = db.variableDao().getInProject(variableName, spec.variableProjectId)
                    ?: return LocalePluginConditionState.Unknown
                LocaleConditionEvaluator.evaluate(
                    spec,
                    LocaleConditionSnapshot(
                        variableExists = true,
                        variableSecret = entity.isSecret,
                        variableValue = entity.value,
                    ),
                )
            }
        }
    }

    private companion object {
        const val MAX_QUERY_MS = 5_000L
    }
}
