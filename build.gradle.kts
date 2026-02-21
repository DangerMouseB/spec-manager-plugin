plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "sm"
version = "2026.02.23.1"

repositories { mavenCentral() }

dependencies { }

kotlin { jvmToolchain(17) }

intellij {
    // Build against IC (IntelliJ Community) 2024.1 — CLion/PyCharm artifacts don't resolve.
    // untilBuild allows the zip to install into 2025.3 IDEs.
    type.set("IC")
    version.set("2024.1.7")
    plugins.set(listOf())
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("253.*")  // allows install into 2024.1 through 2025.3
    }

    // The built-in runIde crashes with "Index: 1, Size: 1" on JetBrains 2025.3 IDEs
    // (gradle-intellij-plugin 1.x can't parse the new product-info.json layout).
    // Use ./run.sh instead — see README.
    runIde { enabled = false }

    buildSearchableOptions { enabled = false }
}

