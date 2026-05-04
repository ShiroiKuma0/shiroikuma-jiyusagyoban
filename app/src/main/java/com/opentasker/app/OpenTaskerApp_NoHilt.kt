package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.automation.action.impl.IntentAction
import com.opentasker.automation.action.impl.NotificationAction
import com.opentasker.automation.action.impl.ShellAction
import com.opentasker.automation.constraint.impl.NetworkConstraint
import com.opentasker.automation.constraint.impl.ScreenStateConstraint
import com.opentasker.automation.constraint.impl.TimeConstraint
import com.opentasker.automation.constraint.impl.VariableConstraint
import com.opentasker.automation.core.AutomationEngine
import com.opentasker.automation.core.DefaultActionRegistry
import com.opentasker.automation.core.DefaultConstraintEvaluator
import com.opentasker.automation.core.DefaultConstraintRegistry
import com.opentasker.automation.core.DefaultTriggerMatcher
import com.opentasker.automation.core.DefaultTriggerRegistry
import com.opentasker.automation.data.AutomationDatabase
import com.opentasker.automation.data.repository.AutomationRuleRepository
import com.opentasker.automation.data.repository.ExecutionLogRepository
import com.opentasker.automation.trigger.impl.AppOpenTrigger
import com.opentasker.automation.trigger.impl.BatteryTrigger
import com.opentasker.automation.trigger.impl.GeofenceTrigger
import com.opentasker.automation.trigger.impl.TimeTrigger
import com.opentasker.automation.trigger.impl.WiFiTrigger
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseMigrations

// Application singleton keeps startup deterministic while Hilt is not active.
class OpenTaskerApp_NoHilt : Application() {
    companion object {
        private var _db: AppDatabase? = null
        private var _automationDb: AutomationDatabase? = null
        private var _automationEngine: AutomationEngine? = null
        
        val db: AppDatabase
            get() {
                if (_db == null) {
                    throw IllegalStateException("Database not initialized.")
                }
                return requireNotNull(_db)
            }

        val automationEngine: AutomationEngine
            get() {
                if (_automationEngine == null) {
                    throw IllegalStateException("Automation engine not initialized.")
                }
                return requireNotNull(_automationEngine)
            }
    }

    override fun onCreate() {
        super.onCreate()
        
        if (_db == null) {
            _db = Room.databaseBuilder(
                this,
                AppDatabase::class.java,
                "opentasker.db"
            )
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                .build()
        }
        if (_automationDb == null) {
            _automationDb = Room.databaseBuilder(
                this,
                AutomationDatabase::class.java,
                "automation.db"
            ).build()
        }
        if (_automationEngine == null) {
            _automationEngine = createAutomationEngine()
        }
    }

    private fun createAutomationEngine(): AutomationEngine {
        val automationDb = requireNotNull(_automationDb)
        val triggerRegistry = DefaultTriggerRegistry().apply {
            register(TimeTrigger())
            register(WiFiTrigger())
            register(BatteryTrigger())
            register(GeofenceTrigger())
            register(AppOpenTrigger())
        }
        val constraintRegistry = DefaultConstraintRegistry().apply {
            register(TimeConstraint())
            register(ScreenStateConstraint(this@OpenTaskerApp_NoHilt))
            register(VariableConstraint())
            register(NetworkConstraint(this@OpenTaskerApp_NoHilt))
        }
        val actionRegistry = DefaultActionRegistry().apply {
            register(NotificationAction(this@OpenTaskerApp_NoHilt))
            register(IntentAction(this@OpenTaskerApp_NoHilt))
            register(ShellAction())
        }

        return AutomationEngine(
            this,
            AutomationRuleRepository(automationDb.automationRuleDao()),
            ExecutionLogRepository(automationDb.executionLogDao()),
            triggerRegistry,
            constraintRegistry,
            actionRegistry,
            DefaultTriggerMatcher(),
            DefaultConstraintEvaluator()
        )
    }
}
