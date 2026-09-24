import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "tr.borsatakip.v5"
    compileSdk = 35

    val defaultBackendUrl = listOf(
        providers.gradleProperty("borsa.backendUrl").orNull,
        providers.gradleProperty("BORSA_BACKEND_URL").orNull,
        providers.gradleProperty("BORSA_PRODUCTION_BACKEND_URL").orNull,
        System.getenv("BORSA_BACKEND_URL"),
        System.getenv("BORSA_PRODUCTION_BACKEND_URL")
    ).firstOrNull { !it.isNullOrBlank() }?.trim()?.removeSuffix("/").orEmpty()
    val escapedDefaultBackendUrl = defaultBackendUrl
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    val attestationPublicKeys = listOf(
        providers.gradleProperty("borsa.attestationPublicKeys").orNull,
        providers.gradleProperty("BORSA_ATTESTATION_PUBLIC_KEYS").orNull,
        System.getenv("BORSA_ATTESTATION_PUBLIC_KEYS")
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    val escapedAttestationPublicKeys = attestationPublicKeys
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    val retiredBackendHosts = listOf(
        providers.gradleProperty("borsa.retiredBackendHosts").orNull,
        providers.gradleProperty("BORSA_RETIRED_BACKEND_HOSTS").orNull,
        System.getenv("BORSA_RETIRED_BACKEND_HOSTS")
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    val escapedRetiredBackendHosts = retiredBackendHosts
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    defaultConfig {
        applicationId = "tr.borsatakip.v5"
        minSdk = 26
        targetSdk = 35
        versionCode = 556
        versionName = "5.4.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$escapedDefaultBackendUrl\"")
        buildConfigField("String", "ATTESTATION_PUBLIC_KEYS", "\"$escapedAttestationPublicKeys\"")
        buildConfigField("String", "RETIRED_BACKEND_HOSTS", "\"$escapedRetiredBackendHosts\"")
    }

    val releaseStorePath = System.getenv("BORSA_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("BORSA_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("BORSA_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("BORSA_KEY_PASSWORD")
    val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseSecure") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            if (isReleaseTaskRequested) {
                check(defaultBackendUrl.isNotBlank()) { "Release/production build için BORSA_BACKEND_URL veya -Pborsa.backendUrl zorunludur." }
                val releaseUri = runCatching { URI(defaultBackendUrl) }.getOrNull()
                check(releaseUri != null && releaseUri.scheme.equals("https", ignoreCase = true) && !releaseUri.host.isNullOrBlank() && releaseUri.userInfo == null && releaseUri.fragment == null) {
                    "Release/production backend URL geçerli bir HTTPS adresi olmalıdır."
                }
                check(releaseUri.host !in setOf("localhost", "127.0.0.1", "10.0.2.2")) { "Release/production backend localhost/emülatör adresi olamaz." }
                check(attestationPublicKeys.isNotBlank()) { "Release/production build için BORSA_ATTESTATION_PUBLIC_KEYS zorunludur." }
                check(attestationPublicKeys.split(';').filter { it.isNotBlank() }.all { entry ->
                    val parts = entry.split(':', limit = 4)
                    parts.size == 4 && parts[0].isNotBlank() && parts[1].isNotBlank() && (parts[2].toLongOrNull() ?: 0L) > 0L && parts[3].isNotBlank()
                }) { "BORSA_ATTESTATION_PUBLIC_KEYS biçimi providerId:keyId:keyGeneration:base64PublicKey olmalıdır." }
                check(hasReleaseSigning) { "Release APK üretimi için güvenli imzalama değişkenleri zorunludur." }
                signingConfig = signingConfigs.getByName("releaseSecure")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
