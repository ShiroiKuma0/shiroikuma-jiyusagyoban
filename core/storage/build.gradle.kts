plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.opentasker.core.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").kotlin.directories.add("$rootDir/app/src/main/java/com/opentasker/core/storage")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$rootDir/app/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core:model"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
