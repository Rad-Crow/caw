plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val abi = providers.gradleProperty("abi").orNull
// order matters: f-droid installs the highest compatible versionCode
val abiCodes = mapOf("armeabi-v7a" to 1, "x86" to 2, "arm64-v8a" to 3, "x86_64" to 4)

android {
    namespace = "com.radcrow.caw"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.radcrow.caw"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
        versionCode = versionCode!! * 10 + (abi?.let { abiCodes.getValue(it) } ?: 0)
        abi?.let { a -> ndk { abiFilters += a } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // shared debug key for container and IDE builds so installs update in place
    rootProject.file("debug.keystore").takeIf { it.exists() }?.let { ks ->
        signingConfigs.getByName("debug") { storeFile = ks }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // the verification word list is shared with the web app
    val protocol = rootProject.file("../protocol")
    check(protocol.resolve("words.txt").exists()) { "missing ../protocol/words.txt, build from the full repo" }
    sourceSets["main"].assets.srcDir(protocol)
    sourceSets["androidTest"].assets.srcDir(rootProject.file("../testdata/media"))

    // f-droid rejects the signed google dependency blob
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.webrtc)
    implementation(libs.unifiedpush)
    implementation(libs.bouncycastle)
    implementation(libs.core.telecom)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.muxer)
    implementation(libs.work)
    implementation(libs.zxing)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
