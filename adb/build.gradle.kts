plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
}
