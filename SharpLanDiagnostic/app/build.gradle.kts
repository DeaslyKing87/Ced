plugins { id("com.android.application") }

android {
    namespace = "com.ced.sharplan"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ced.sharplan"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-diagnostic"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
