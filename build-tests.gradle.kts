// Standalone test build that bypasses the IntelliJ Platform plugin
// Runs all tests that don't depend on IntelliJ APIs

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

repositories {
    maven("https://repo1.maven.org/maven2/")
}

val intellijStubs = files("/tmp/intellij-stubs.jar")

kotlin {
    jvmToolchain(21)
}

// Configure source sets to include only non-plugin source files
sourceSets {
    main {
        kotlin {
            setSrcDirs(listOf(
                "src/main/kotlin/com/codepattern/analysis",
                "src/main/kotlin/com/codepattern/models",
                "src/main/kotlin/com/codepattern/patterns",
                "src/main/kotlin/com/codepattern/report",
                "src/main/kotlin/com/codepattern/scanner",
                "src/main/kotlin/com/codepattern/standalone",
                "src/main/kotlin/com/codepattern/lsp"
            ))
            exclude(
                "**/ProjectScanner.kt",
                "**/ProjectScannerService.kt"
            )
        }
        resources {
            setSrcDirs(listOf("src/main/resources"))
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf("src/test/kotlin"))
            exclude("**/PatternLoaderTest*")
        }
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation(intellijStubs)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    testImplementation(intellijStubs)
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showCauses = true
        showExceptions = true
    }
}
