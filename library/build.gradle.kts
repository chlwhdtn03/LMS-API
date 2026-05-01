import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)

    kotlin("plugin.serialization") version "2.3.0"
}

group = "io.github.chlwhdtn03"
version = "1.2.2"

val ktor_version: String by project

kotlin {
    val xcf = XCFramework("LmsApi")

    jvm()
    androidLibrary {
        namespace = "io.github.chlwhdtn03.lms"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }
    macosArm64()
    val iosX64 = iosX64()
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()
//    linuxX64()

    listOf(iosX64, iosArm64, iosSimulatorArm64).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "LmsApi"
            binaryOption("bundleId", "io.github.chlwhdtn03.lms")
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
        }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("io.ktor:ktor-client-cio:${ktor_version}")
            implementation("io.ktor:ktor-client-core:${ktor_version}")
            implementation("io.ktor:ktor-client-content-negotiation:${ktor_version}")
            implementation("io.ktor:ktor-serialization-kotlinx-json:${ktor_version}")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }

        androidMain.dependencies {
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
            // implementation("io.ktor:ktor-client-cio:${ktor_version}")
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        macosArm64Main.dependencies {
            implementation(libs.ktor.client.darwin)
        }

    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "lms", version.toString())

    pom {
        name = "LMS-API"
        description = "LMS 정보를 가져오는 라이브러리 입니다."
        inceptionYear = "2026"
        url = "https://github.com/chlwhdtn03/LMS-API"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "chlwhdtn03"
                name = "CHOI JONGSU"
                url = "https://github.com/chlwhdtn03"
            }
        }
        scm {
            url = "https://github.com/chlwhdtn03/LMS-API"
            connection = "scm:git:git://github.com/chlwhdtn03/LMS-API.git"
            developerConnection = "scm:git:ssh://git@github.com/chlwhdtn03/LMS-API.git"
        }
    }
}
