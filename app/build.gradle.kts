import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing values come from environment variables (CI) or keystore.properties (local).
// Neither is committed to git.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val appVersion: String = (findProperty("pluslock.version") as String?) ?: "0.1.0"

// Must match core/Versions.kt so the updater and Android agree on ordering.
// 1.2.3 -> 1020399, 1.2.3-beta.4 -> 1020304
fun versionCodeFor(name: String): Int {
    val clean = name.trim().removePrefix("v")
    val core = clean.substringBefore("-")
    val pre = if (clean.contains("-")) clean.substringAfter("-") else null
    val nums = core.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val preNum = if (pre == null) 99 else (Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0).coerceIn(0, 98)
    return nums[0] * 1_000_000 + nums[1].coerceAtMost(99) * 10_000 + nums[2].coerceAtMost(99) * 100 + preNum
}

// The privacy policy, terms and licence live in the repo root; the app shows the same files.
val copyLegal by tasks.registering(Copy::class) {
    from(rootProject.files("PRIVACY.md", "TERMS.md", "LICENSE"))
    into(layout.buildDirectory.dir("generated/legal/legal"))
}
tasks.named("preBuild") { dependsOn(copyLegal) }

android {
    namespace = "io.github.nugggy.pluslock"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nugggy.pluslock"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeFor(appVersion)
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"nugggy/PlusLock\"")
    }

    val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
    signingConfigs {
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal"))
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
