plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// BASTION Link: the ONLY BASTION component with the INTERNET permission. It holds no
// library, AI or message data — it connects to the Meshtastic app's local TAK server
// (127.0.0.1) and relays CoT events to BASTION over a signature-protected binding.
android {
    namespace = "com.bastion.link"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bastion.link"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as BASTION: the signature permission between the two depends on it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
