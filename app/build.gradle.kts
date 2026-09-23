import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
}

// No secrets live in this project. Without this opt-in, development/test builds work
// normally and assembleRelease produces an unsigned artifact, never a debug-signed one.
val signingPropertiesPath = providers.environmentVariable("NAIGRE_SIGNING_PROPERTIES").orNull
val releaseCredentials = Properties().apply {
    signingPropertiesPath?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}
val signingFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseCredentials = signingFields.all { !releaseCredentials.getProperty(it).isNullOrBlank() }

abstract class LicenseAssetsTask : Sync() {
    @get:OutputDirectory
    abstract val assetOutputDirectory: DirectoryProperty
}

val prepareLicenseAssets = tasks.register<LicenseAssetsTask>("prepareLicenseAssets") {
    assetOutputDirectory.set(layout.buildDirectory.dir("generated/licenseAssets"))
    into(assetOutputDirectory)
    into("licenses") {
        from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
        from(rootProject.file("licenses"))
    }
}

android {
    namespace = "wund0r.naigre.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "wund0r.naigre.reader"
        minSdk = 26
        targetSdk = 36
        // YY.MM.release: increment the final number for every release within the month.
        // versionCode never resets, even when the calendar month changes.
        versionCode = 64
        versionName = "26.09.1"
        testInstrumentationRunner = "wund0r.naigre.reader.LocalizationTestRunner"
    }

    signingConfigs {
        if (hasReleaseCredentials) {
            create("publicRelease") {
                storeFile = file(releaseCredentials.getProperty("storeFile"))
                storePassword = releaseCredentials.getProperty("storePassword")
                keyAlias = releaseCredentials.getProperty("keyAlias")
                keyPassword = releaseCredentials.getProperty("keyPassword")
                storeType = releaseCredentials.getProperty("storeType", "PKCS12")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += listOf("debug")
        }
        release {
            isDebuggable = false
            if (hasReleaseCredentials) signingConfig = signingConfigs.getByName("publicRelease")
            // Keep the prototype release path predictable. R8/resource shrinking can be enabled
            // later as a separate, measured change after MuPDF and Markwon rules are validated.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // Instrumentation installs a disposable target package and can never uninstall user data.
    testBuildType = "verification"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLicenseAssets, LicenseAssetsTask::assetOutputDirectory)
}

// LocalizationCatalogTest reads source XML directly; text-only edits must rerun it
// even when generated R symbols and the unit-test classpath are unchanged.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(
        "src/main/AndroidManifest.xml",
        "src/main/res/xml/locale_config.xml",
        "src/main/res/values/strings.xml",
        "src/main/res/values-ru/strings.xml",
    ).withPropertyName("localizationCatalog").withPathSensitivity(PathSensitivity.RELATIVE)
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

fun releaseCertificate(): X509Certificate {
    check(hasReleaseCredentials) {
        "Release signing is not configured. Set NAIGRE_SIGNING_PROPERTIES; see RELEASING.md."
    }
    val store = KeyStore.getInstance(releaseCredentials.getProperty("storeType", "PKCS12"))
    file(releaseCredentials.getProperty("storeFile")).inputStream().use {
        store.load(it, releaseCredentials.getProperty("storePassword").toCharArray())
    }
    val alias = releaseCredentials.getProperty("keyAlias")
    check(store.isKeyEntry(alias)) { "The release key alias has no private key." }
    check(store.getKey(alias, releaseCredentials.getProperty("keyPassword").toCharArray()) != null)
    val certificate = store.getCertificate(alias) as X509Certificate
    certificate.checkValidity()
    check(!certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true)) {
        "Refusing to package an Android debug signing key as a public release."
    }
    return certificate
}

val checkReleaseKey = tasks.register("checkReleaseKey") {
    group = "release"
    description = "Validate local release credentials without printing secrets."
    doLast { releaseCertificate() }
}

// Run the inexpensive key check first when preparing a public release. Other build
// invocations (including unsigned release lint/build checks) do not require a key.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { mustRunAfter(checkReleaseKey) }

val releaseSdkDirectory = androidComponents.sdkComponents.sdkDirectory
val releaseBuildToolsVersion = android.buildToolsVersion
val publicVersion = android.defaultConfig.versionName!!
val publicVersionCode = android.defaultConfig.versionCode!!
val publicApplicationId = android.defaultConfig.applicationId!!

tasks.register("preparePublicRelease") {
    group = "release"
    description = "Test, lint, build and verify a signed APK; never tag, upload or install it."
    dependsOn(checkReleaseKey, "testVerificationUnitTest", "lintRelease", "assembleRelease")
    doLast {
        check(Regex("[0-9]{2}\\.(0[1-9]|1[0-2])\\.(0|[1-9][0-9]*)").matches(publicVersion)) {
            "Public version must use YY.MM.PATCH, with a zero-padded month."
        }
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(apk.isFile) { "Signed release APK was not produced." }
        val toolsDir = releaseSdkDirectory.get().asFile.resolve("build-tools/$releaseBuildToolsVersion")
        fun runTool(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Release verification failed: ${command.first()}\n$output" }
            return output
        }
        val signingReport = runTool(
            toolsDir.resolve("apksigner").absolutePath, "verify", "--verbose", "--print-certs", apk.absolutePath,
        )
        val fingerprint = sha256(releaseCertificate().encoded)
        val actualFingerprints = Regex("Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)")
            .findAll(signingReport).map { it.groupValues[1].lowercase() }.toList()
        check(actualFingerprints == listOf(fingerprint)) { "APK signer does not match the release key." }
        val badging = runTool(toolsDir.resolve("aapt2").absolutePath, "dump", "badging", apk.absolutePath)
        check(badging.lineSequence().any {
            it.startsWith("package: name='$publicApplicationId' versionCode='$publicVersionCode' versionName='$publicVersion'")
        }) { "APK identity/version does not match the build configuration." }
        check(!badging.contains("application-debuggable")) { "Refusing a debuggable public APK." }
        check(badging.lineSequence().any { it == "application-label:'NaIgre'" }) { "Unexpected launcher label." }
        runTool(toolsDir.resolve("zipalign").absolutePath, "-c", "-P", "16", "4", apk.absolutePath)

        val destination = rootProject.layout.buildDirectory.dir("public-release/$publicVersion").get().asFile
        destination.mkdirs()
        val target = destination.resolve("NaIgre-$publicVersion.apk")
        apk.copyTo(target, overwrite = true)
        val checksum = sha256(target.readBytes())
        destination.resolve("${target.name}.sha256").writeText("$checksum  ${target.name}\n")
        rootProject.file("LICENSE").copyTo(destination.resolve("LICENSE"), overwrite = true)
        rootProject.file("NOTICE").copyTo(destination.resolve("NOTICE"), overwrite = true)
        rootProject.file("licenses").copyRecursively(destination.resolve("licenses"), overwrite = true)
        val commit = runTool("git", "-C", rootDir.absolutePath, "rev-parse", "HEAD").trim()
        val dirty = runTool("git", "-C", rootDir.absolutePath, "status", "--porcelain").isNotBlank()
        destination.resolve("release-info.txt").writeText(
            "NaIgre $publicVersion ($publicVersionCode)\n" +
                "Application ID: $publicApplicationId\n" +
                "Signing certificate SHA-256: $fingerprint\n" +
                "APK SHA-256: $checksum\nCommit: $commit\nUncommitted changes: $dirty\n" +
                "Device acceptance and an independent signing-key backup must be checked manually.\n",
        )
        logger.lifecycle("Verified release candidate: ${target.absolutePath}")
        if (dirty) logger.warn("Candidate includes uncommitted changes. Commit and rebuild before tagging/publishing.")
    }
}

tasks.register("createReleaseKey") {
    group = "release"
    description = "Create a NEW local signing directory; refuses existing paths and never prints passwords."
    doLast {
        val directory = providers.gradleProperty("naigreKeyDirectory").orNull?.let { file(it).canonicalFile }
            ?: error("Specify -PnaigreKeyDirectory=/absolute/path/outside/the/repository (see RELEASING.md).")
        check(!directory.toPath().startsWith(rootDir.canonicalFile.toPath())) { "Keep signing material outside the repository." }
        check(!directory.exists()) { "Signing directory already exists; refusing to overwrite it." }
        Files.createDirectories(directory.parentFile.toPath())
        Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
        )
        val storeFile = directory.resolve("naigre-release.p12")
        val credentialsFile = directory.resolve("release.properties")
        val credentials = Properties().apply {
            setProperty("storeFile", storeFile.absolutePath)
            setProperty("storeType", "PKCS12")
            setProperty("storePassword", password)
            setProperty("keyAlias", "naigre")
            setProperty("keyPassword", password)
        }
        // Save credentials first so even an interrupted keytool run cannot strand a key.
        Files.createFile(credentialsFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.newOutputStream(credentialsFile.toPath(), StandardOpenOption.WRITE).use {
            credentials.store(it, "PRIVATE NaIgre signing configuration. Back up securely; never commit or share.")
        }
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/keytool").absolutePath,
            "-genkeypair", "-noprompt", "-keystore", storeFile.absolutePath, "-storetype", "PKCS12",
            "-alias", "naigre", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10950",
            "-dname", "CN=NaIgre", "-storepass:env", "NAIGRE_NEW_KEY_PASSWORD", "-keypass:env", "NAIGRE_NEW_KEY_PASSWORD",
        ).apply { environment()["NAIGRE_NEW_KEY_PASSWORD"] = password }.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Key generation failed. Keep $credentialsFile for recovery.\n$output" }
        Files.setPosixFilePermissions(storeFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        logger.lifecycle("Created signing key and credentials in $directory. Passwords were not printed.")
        logger.lifecycle("Set NAIGRE_SIGNING_PROPERTIES=$credentialsFile, then back up BOTH files securely on another device.")
    }
}

dependencies {
    implementation("com.artifex.mupdf:fitz:1.28.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
