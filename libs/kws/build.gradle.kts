plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.xxstudy.kws"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 编译时绑定 sherpa-onnx 引擎 (优先本模块 libs，若无则索引根项目 app/libs，防止打包冲突)
    val localLibs = fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar")))
    val rootLibs = fileTree(mapOf("dir" to "${project.rootDir}/app/libs", "include" to listOf("*.aar", "*.jar")))
    compileOnly(localLibs)
    compileOnly(rootLibs)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}