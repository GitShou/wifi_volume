plugins {
    alias(libs.plugins.android.application)
}

providers.gradleProperty("codexAppBuildDir").orNull?.let { buildDirOverride ->
    layout.buildDirectory.set(file(buildDirOverride))
}

android {
    namespace = "com.example.wifi_volume"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.wifi_volume"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testBuildType = "e2e"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        animationsDisabled = true
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}
