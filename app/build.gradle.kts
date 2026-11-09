import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.haokai.addemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haokai.addemo"
        minSdk = 24
        targetSdk = 33
        versionCode = 4
        versionName = "0.4.0-voice-b"

        fun prop(key: String, default: String = ""): String =
            localProperties.getProperty(key, default).replace("\"", "\\\"")
        buildConfigField("String", "VOLC_API_KEY", "\"${prop("volc.apiKey")}\"")
        buildConfigField("String", "VOLC_APP_KEY", "\"${prop("volc.appKey")}\"")
        buildConfigField("String", "VOLC_ACCESS_KEY", "\"${prop("volc.accessKey")}\"")
        buildConfigField(
            "String",
            "VOLC_RESOURCE_ID",
            "\"${prop("volc.resourceId", "volc.seedasr.sauc.duration")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_WS_URL",
            "\"${prop("volc.wsUrl", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")}\"",
        )
        buildConfigField(
            "String",
            "DEVICE_API_BASE",
            "\"${prop("device.apiBase", "https://api.ulandusa.com")}\"",
        )
        buildConfigField(
            "String",
            "DEVICE_SERIAL",
            "\"${prop("device.serialNumber")}\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/librk_dl_jni.so",
                "**/libc++_shared.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    androidResources {
        noCompress += "rknn"
        noCompress += listOf("mp4", "png")
    }

    ndkVersion = "27.0.12077973"
}

val syncRkDlJniLib = tasks.register<Copy>("syncRkDlJniLib") {
    description = "Copy CMake-built librk_dl_jni.so into jniLibs/arm64-v8a"
    from(layout.buildDirectory.dir("intermediates/cxx"))
    include("**/obj/arm64-v8a/librk_dl_jni.so")
    into("src/main/jniLibs/arm64-v8a")
    eachFile {
        path = name
    }
    includeEmptyDirs = false
    onlyIf {
        layout.buildDirectory.dir("intermediates/cxx").get().asFile
            .walkTopDown()
            .any { it.name == "librk_dl_jni.so" && it.length() > 4096L }
    }
}

tasks.matching {
    it.name == "externalNativeBuildDebug" || it.name == "externalNativeBuildRelease"
}.configureEach {
    finalizedBy(syncRkDlJniLib)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.gson)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(files("libs/audioprocessor.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
