import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: use the team key when keystore.properties (git-ignored) exists;
// otherwise sign with the debug key so the release APK is still installable for judging.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

val teamName: String = providers.gradleProperty("haegopa.teamName").getOrElse("TEAM")

android {
    namespace = "com.haegopa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haegopa.app"
        minSdk = 28 // 공모전 · Pleos Connect 요구사항: Android API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"
    }

    // AAOS Car API (CarPropertyManager → VHAL PERF_VEHICLE_SPEED).
    // 컴파일은 SDK 플랫폼의 optional/android.car.jar 로, 런타임 구현은 차량 OS(Pleos Connect)가 제공한다.
    useLibrary("android.car")

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pleos SDK uses reflection/serialization internally, so the prototype ships without R8 shrinking.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pleos Connect SDK — Vehicle(기어·주행시간) / NaviHelper(경유지) / TTS / STT / FusedLocation(차 현재 위치)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.fusedlocation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Competition submission: copy the release APK to apk/{TeamName}_HAEGOPA_release.apk
val exportCompetitionApk by tasks.registering(Copy::class) {
    group = "haegopa"
    description = "Builds the release APK and copies it to apk/{TeamName}_HAEGOPA_release.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "${teamName}_HAEGOPA_release.apk" }
}
