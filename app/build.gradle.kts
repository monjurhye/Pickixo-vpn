import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google's official test IDs: safe for development, never earn money.
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerId = "ca-app-pub-3940256099942544/9214589741"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"

fun prop(name: String): String = (findProperty(name) as String?)?.trim().orEmpty()

val realAppId = prop("PICKIXO_ADMOB_APP_ID")
val realBannerId = prop("PICKIXO_BANNER_ID")
val realInterstitialId = prop("PICKIXO_INTERSTITIAL_ID")
val privacyUrl = prop("PICKIXO_PRIVACY_URL")

// Refuse to build a release with test ads or without a privacy policy (both get apps rejected).
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (buildingRelease) {
    val missing = listOf(
        "PICKIXO_ADMOB_APP_ID" to realAppId, "PICKIXO_BANNER_ID" to realBannerId,
        "PICKIXO_INTERSTITIAL_ID" to realInterstitialId, "PICKIXO_PRIVACY_URL" to privacyUrl,
    ).filter { it.second.isEmpty() }.map { it.first }
    if (missing.isNotEmpty()) {
        throw GradleException("Set these in gradle.properties before a release build: ${missing.joinToString()}")
    }
}

android {
    namespace = "com.pickixo.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pickixo.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = testAppId
        buildConfigField("String", "BANNER_ID", "\"$testBannerId\"")
        buildConfigField("String", "INTERSTITIAL_ID", "\"$testInterstitialId\"")
        buildConfigField("String", "PRIVACY_URL", "\"${privacyUrl.ifEmpty { "https://www.vpngate.net/en/about_abuse.aspx" }}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["admobAppId"] = realAppId.ifEmpty { testAppId }
            buildConfigField("String", "BANNER_ID", "\"${realBannerId.ifEmpty { testBannerId }}\"")
            buildConfigField("String", "INTERSTITIAL_ID", "\"${realInterstitialId.ifEmpty { testInterstitialId }}\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
}
