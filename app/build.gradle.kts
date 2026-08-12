plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.let { value ->
    value.toIntOrNull() ?: error("releaseVersionCode must be an integer, got: $value")
}
val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.retrsoft.bilisponsorskip"
    compileSdk = 35

    sourceSets {
        getByName("main").res.srcDir("../assets/android-res")
    }

    defaultConfig {
        applicationId = "com.retrsoft.bilisponsorskip"
        minSdk = 24
        targetSdk = 35
        versionCode = releaseVersionCode ?: 6
        versionName = releaseVersionName ?: "0.2.4"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        // LSPosed loads module native libraries directly from base.apk. They
        // therefore need to stay uncompressed and page-aligned for the linker.
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.preference:preference:1.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
