import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 인증키는 소스에 넣지 않는다. local.properties 또는 CI 환경변수에서 읽어
 * BuildConfig 로만 노출한다. 둘 다 없으면 빈 문자열로 빌드는 되지만,
 * 앱 실행 시 [kr.eodiga.wayfinder.data.remote.ServiceKeyProvider] 가
 * "인증키 없음" 상태를 UI 로 알려준다.
 */
fun secret(name: String): String {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val props = Properties().apply { local.inputStream().use { load(it) } }
        props.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return System.getenv(name).orEmpty()
}

android {
    namespace = "kr.eodiga.wayfinder"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.eodiga.wayfinder"
        // 26: 포그라운드 서비스 알림 채널 + 진동 이펙트 API 를 안정적으로 쓰기 위한 하한.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PUBLIC_DATA_SERVICE_KEY", "\"${secret("PUBLIC_DATA_SERVICE_KEY")}\"")
        buildConfigField("String", "JUSO_CONFIRM_KEY", "\"${secret("JUSO_CONFIRM_KEY")}\"")
        buildConfigField("String", "SEOUL_OPEN_API_KEY", "\"${secret("SEOUL_OPEN_API_KEY")}\"")

        resourceConfigurations += listOf("ko")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.play.services.location)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
