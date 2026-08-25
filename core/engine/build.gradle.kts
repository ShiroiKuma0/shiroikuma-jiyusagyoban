plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // TaskFailure carries @Serializable types; without this the module compiles them with no
    // generated serializer and every structured failure throws at runtime.
    alias(libs.plugins.kotlin.serialization)
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
}

kotlin {
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
    testImplementation(libs.junit)
    // The Compose compiler plugin runs over the unit-test source set too and refuses to
    // compile without the runtime on the class path, even though nothing here is a composable.
    testCompileOnly(platform(libs.androidx.compose.bom))
    testCompileOnly(libs.androidx.compose.runtime)
}
