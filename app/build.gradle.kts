import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sqftware.safegit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sqftware.safegit"
        minSdk = 30
        targetSdk = 37
        versionName = "0.1.0"

        // The bundled git is arm64 only, so a device of another ABI must not install the app at all
        ndk {
            abiFilters += "arm64-v8a"
        }

        // The GitHub OAuth app's client id is public, as the device flow is built for apps that cannot keep a secret
        resValue("string", "github_client_id", providers.gradleProperty("safegit.githubClientId").getOrElse(""))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    packaging {
        jniLibs {
            // git and its helpers are executables, so they must be extracted to nativeLibraryDir to be run
            useLegacyPackaging = true
            // Stripping needs the NDK, and Termux already ships them stripped
            keepDebugSymbols += "**/*.so"
        }
    }
}

/** Fetches Termux's git build into the APK; run with --rerun to pick up a newer one. */
abstract class FetchGit : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun fetch() {
        exec.exec { commandLine(script.get().asFile, jniLibs.get().asFile, assets.get().asFile) }
    }
}

val fetchGit = tasks.register<FetchGit>("fetchGit") {
    script = rootProject.layout.projectDirectory.file("scripts/fetch-git.sh")
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchGit, FetchGit::jniLibs)
        variant.sources.assets?.addGeneratedSourceDirectory(fetchGit, FetchGit::assets)
    }
}

apply(from = "../scripts/android-app.gradle")

dependencies {
    implementation(project(":sync"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // Runs each sync as expedited work that outlives the screen
    implementation(libs.androidx.work.runtime)

    // Declares the empty ComponentActivity that Compose UI tests render a single composable into
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    // The real org.json, in place of android.jar's stubs, so the app's JSON files are tested on the JVM
    testImplementation(libs.json)

    androidTestImplementation(testFixtures(project(":sync")))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ui-test-junit4 only brings Espresso 3.5.0 at runtime, which cannot inject input on API 34 and later
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
