plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun secret(name: String): String? = System.getenv(name) ?: (findProperty(name) as String?)

/**
 * The release key. A build machine can pass it in the environment. A machine that keeps a copy in ~/.config/nano-chat
 * signs its local builds with it, so they install over each other. Without either, a release build uses the debug key.
 * The key was this project's first debug key, so it keeps that alias and installs over the first builds.
 */
val localKey = File(System.getProperty("user.home"), ".config/nano-chat/release.jks")
val keystore: String? = secret("NANOCHAT_KEYSTORE") ?: localKey.takeIf { it.exists() }?.path
val keystorePassword: String? = secret("NANOCHAT_KEYSTORE_PASSWORD")
    ?: File(localKey.parentFile, "release.password").takeIf { it.exists() }?.readText()?.trim()
val releaseKeyAlias: String = secret("NANOCHAT_KEY_ALIAS") ?: "androiddebugkey"

/**
 * The minutes since 2026-01-01, for every build, so whichever build is newer installs over the other. It runs when a
 * task reads the version code, so a reused configuration cache gets a new value.
 */
abstract class MinutesVersionCode : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int = ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt()
}

android {
    namespace = "ai.tushar.nanochat"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.tushar.nanochat"
        minSdk = 31
        targetSdk = 37
        versionName = "0.1.0"
    }

    signingConfigs {
        // Every release build must carry the same key, or Android refuses the update
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        val versionCode = providers.of(MinutesVersionCode::class) {}
        variant.outputs.forEach { it.versionCode.set(versionCode) }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.litertlm.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
