plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mihomoVersion = "v1.19.25"
val assetsDir = file("src/main/assets")

task("downloadMihomo") {
    val outputFile = File(assetsDir, "mihomo-arm64")
    doLast {
        if (!outputFile.exists()) {
            val url = java.net.URL("https://github.com/MetaCubeX/mihomo/releases/download/$mihomoVersion/mihomo-android-arm64-v8-$mihomoVersion.gz")
            println("Downloading mihomo from $url ...")
            url.openStream().use { input ->
                java.util.zip.GZIPInputStream(input).use { gzip ->
                    outputFile.outputStream().use { output -> gzip.copyTo(output) }
                }
            }
            outputFile.setExecutable(true)
            println("mihomo downloaded: ${outputFile.length()} bytes")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn("downloadMihomo")
}

android {
    namespace = "com.jixvpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jixvpn.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
}
