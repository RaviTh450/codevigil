plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.codepattern"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")

    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codepattern.analyzer"
        name = "Code Pattern Analyzer"
        version = "1.0.0"

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "251.*"
        }
    }

    // Plugin signing for marketplace (set env vars to enable)
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace publishing (set env var PUBLISH_TOKEN)
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
}

// Standalone CLI/LSP JAR (fat jar with all dependencies)
tasks.register<Jar>("cliJar") {
    archiveClassifier.set("cli")
    manifest {
        attributes["Main-Class"] = "com.codepattern.standalone.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") && !it.path.contains("intellij") && !it.path.contains("platform") }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
