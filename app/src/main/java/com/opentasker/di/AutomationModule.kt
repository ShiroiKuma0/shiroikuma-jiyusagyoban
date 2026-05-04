package com.opentasker.di

import android.content.Context
import androidx.room.Room
import com.opentasker.automation.core.AutomationEngine
import com.opentasker.automation.core.DefaultActionExecutor
import com.opentasker.automation.core.DefaultActionRegistry
import com.opentasker.automation.core.DefaultConstraintEvaluator
import com.opentasker.automation.core.DefaultConstraintRegistry
import com.opentasker.automation.core.DefaultTriggerMatcher
import com.opentasker.automation.core.DefaultTriggerRegistry
import com.opentasker.automation.action.impl.IntentAction
import com.opentasker.automation.action.impl.NotificationAction
import com.opentasker.automation.action.impl.ShellAction
import com.opentasker.automation.constraint.impl.NetworkConstraint
import com.opentasker.automation.constraint.impl.ScreenStateConstraint
import com.opentasker.automation.constraint.impl.TimeConstraint
import com.opentasker.automation.constraint.impl.VariableConstraint
import com.opentasker.automation.core.ActionExecutor
import com.opentasker.automation.core.ActionRegistry
import com.opentasker.automation.core.ConstraintEvaluator
import com.opentasker.automation.core.ConstraintRegistry
import com.opentasker.automation.core.TriggerMatcher
import com.opentasker.automation.core.TriggerRegistry
import com.opentasker.automation.data.repository.AutomationRuleRepository
import com.opentasker.automation.data.repository.ExecutionLogRepository
import com.opentasker.automation.data.AutomationDatabase
import com.opentasker.automation.data.dao.AutomationRuleDao
import com.opentasker.automation.data.dao.ExecutionLogDao
import com.opentasker.automation.trigger.impl.AppOpenTrigger
import com.opentasker.automation.trigger.impl.BatteryTrigger
import com.opentasker.automation.trigger.impl.GeofenceTrigger
import com.opentasker.automation.trigger.impl.TimeTrigger
import com.opentasker.automation.trigger.impl.WiFiTrigger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for automation engine.
 * Registers all triggers, constraints, actions, and core components.
 */
@Module
@InstallIn(SingletonComponent::class)
object AutomationModule {

    @Singleton
    @Provides
    fun provideAutomationDatabase(@ApplicationContext context: Context): AutomationDatabase {
        return Room.databaseBuilder(
            context,
            AutomationDatabase::class.java,
            "automation.db"
        ).build()
    }

    @Singleton
    @Provides
    fun provideAutomationRuleDao(db: AutomationDatabase): AutomationRuleDao = db.automationRuleDao()

    @Singleton
    @Provides
    fun provideExecutionLogDao(db: AutomationDatabase): ExecutionLogDao = db.executionLogDao()

    @Singleton
    @Provides
    fun provideAutomationRuleRepository(dao: AutomationRuleDao): AutomationRuleRepository =
        AutomationRuleRepository(dao)

    @Singleton
    @Provides
    fun provideExecutionLogRepository(dao: ExecutionLogDao): ExecutionLogRepository =
        ExecutionLogRepository(dao)

    // ========== REGISTRIES ==========

    @Singleton
    @Provides
    fun provideTriggerRegistry(): TriggerRegistry {
        return DefaultTriggerRegistry().apply {
            register(TimeTrigger())
            register(WiFiTrigger())
            register(BatteryTrigger())
            register(GeofenceTrigger())
            register(AppOpenTrigger())
        }
    }

    @Singleton
    @Provides
    fun provideConstraintRegistry(@ApplicationContext context: Context): ConstraintRegistry {
        return DefaultConstraintRegistry().apply {
            register(TimeConstraint())
            register(ScreenStateConstraint(context))
            register(VariableConstraint())
            register(NetworkConstraint(context))
        }
    }

    @Singleton
    @Provides
    fun provideActionRegistry(@ApplicationContext context: Context): ActionRegistry {
        return DefaultActionRegistry().apply {
            register(NotificationAction(context))
            register(IntentAction(context))
            register(ShellAction())
        }
    }

    // ========== CORE ENGINE COMPONENTS ==========

    @Singleton
    @Provides
    fun provideTriggerMatcher(): TriggerMatcher {
        return DefaultTriggerMatcher()
    }

    @Singleton
    @Provides
    fun provideConstraintEvaluator(): ConstraintEvaluator {
        return DefaultConstraintEvaluator()
    }

    @Singleton
    @Provides
    fun provideActionExecutor(): ActionExecutor {
        return DefaultActionExecutor()
    }

    @Singleton
    @Provides
    fun provideAutomationEngine(
        @ApplicationContext context: Context,
        ruleRepository: AutomationRuleRepository,
        logRepository: ExecutionLogRepository,
        triggerRegistry: TriggerRegistry,
        constraintRegistry: ConstraintRegistry,
        actionRegistry: ActionRegistry,
        triggerMatcher: TriggerMatcher,
        constraintEvaluator: ConstraintEvaluator
    ): AutomationEngine {
        return AutomationEngine(
            context,
            ruleRepository,
            logRepository,
            triggerRegistry,
            constraintRegistry,
            actionRegistry,
            triggerMatcher,
            constraintEvaluator
        )
    }

    // ========== INDIVIDUAL TRIGGERS ==========

    @Provides
    fun provideTimeTrigger(): TimeTrigger = TimeTrigger()

    @Provides
    fun provideWiFiTrigger(): WiFiTrigger = WiFiTrigger()

    @Provides
    fun provideBatteryTrigger(): BatteryTrigger = BatteryTrigger()

    @Provides
    fun provideGeofenceTrigger(): GeofenceTrigger = GeofenceTrigger()

    @Provides
    fun provideAppOpenTrigger(): AppOpenTrigger = AppOpenTrigger()

    // ========== INDIVIDUAL ACTIONS ==========

    @Provides
    fun provideNotificationAction(@ApplicationContext context: Context): NotificationAction =
        NotificationAction(context)

    @Provides
    fun provideIntentAction(@ApplicationContext context: Context): IntentAction =
        IntentAction(context)

    @Provides
    fun provideShellAction(): ShellAction = ShellAction()

    // ========== INDIVIDUAL CONSTRAINTS ==========

    @Provides
    fun provideTimeConstraint(): TimeConstraint = TimeConstraint()

    @Provides
    fun provideScreenStateConstraint(@ApplicationContext context: Context): ScreenStateConstraint =
        ScreenStateConstraint(context)

    @Provides
    fun provideVariableConstraint(): VariableConstraint = VariableConstraint()

    @Provides
    fun provideNetworkConstraint(@ApplicationContext context: Context): NetworkConstraint =
        NetworkConstraint(context)
}
