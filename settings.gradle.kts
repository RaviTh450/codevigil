pluginManagement {
    repositories {
        maven(url = "file:///tmp/local-plugins")
        maven("https://repo1.maven.org/maven2/")
        maven("https://plugins.gradle.org/m2/")
    }
}

rootProject.name = "codevigil"
