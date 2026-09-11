import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties().apply {
    val file = rootProject.file("release-signing.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty("release.$name").orNull
        ?: System.getenv("TICKET_RELEASE_${name.uppercase()}")
        ?: releaseSigningProperties.getProperty(name)

val releaseStoreFile = releaseSigningValue("storeFile")
val releaseStorePassword = releaseSigningValue("storePassword")
val releaseKeyAlias = releaseSigningValue("keyAlias")
val releaseKeyPassword = releaseSigningValue("keyPassword")

android {
    namespace = "com.example.ticketassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ticketassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.1"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Release 构建需要正式签名。请配置 release-signing.properties 或 TICKET_RELEASE_* 环境变量。"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
