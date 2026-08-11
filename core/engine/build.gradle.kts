plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.opentasker.core.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").kotlin.directories.apply {
            clear()
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/ActiveExecutionRegistry.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/AutomationLiveConditionState.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/CausalExecutionTracker.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/CooldownReservations.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/CooldownStore.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/EngineHeartbeatStore.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/ExecutionEnvelope.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/RunLogSource.kt")
            add("$rootDir/app/src/main/java/com/opentasker/core/engine/TaskFailure.kt")
        }
    }
}

kotlin {
    sourceSets {
        configureEach {
            kotlin.exclude(
                "**/Action.kt",
                "**/AutomationService.kt",
                "**/BootReceiver.kt",
                "**/ContextMonitorLifecycle.kt",
                "**/DirectBootTimeScheduler.kt",
                "**/DirectBootTriggerStore.kt",
                "**/EngineWatchdogWorker.kt",
                "**/ExecutionAdmissionController.kt",
                "**/ExecutionAdmissionStrings.kt",
                "**/ExecutionEnvelope.kt",
                "**/ExecutionJournal.kt",
                "**/FlowStructure.kt",
                "**/HeldExecution.kt",
                "**/PreflightRunner.kt",
                "**/ProfileMatcherImpl.kt",
                "**/PulseEventContinuity.kt",
                "**/RunLogDiagnostics.kt",
                "**/RunLogPruneWorker.kt",
                "**/SyntheticTriggerSimulation.kt",
                "**/TaskCollisionCoordinator.kt",
                "**/TaskDispatchPolicy.kt",
                "**/TaskExecutionHelper.kt",
                "**/TaskRunner.kt",
                "**/VariableStore.kt",
            )
        }
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
