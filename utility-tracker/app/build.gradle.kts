plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dowdah.utilitytracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dowdah.utilitytracker"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["size"] = "medium"
    }

    buildTypes {
        create("acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = providers.gradleProperty("acceptanceSuffix").getOrElse(".acceptance")
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testBuildType = "acceptance"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.room:room-testing:2.8.3")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    add("acceptanceImplementation", "androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

// Never let a connected test invocation silently select every attached personal device.
if (gradle.startParameter.taskNames.any { it.contains("connected", ignoreCase = true) } &&
    providers.environmentVariable("ANDROID_SERIAL").orNull.isNullOrBlank()) {
    throw GradleException("Set ANDROID_SERIAL explicitly before running connected device tests")
}
