plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = "chicken-client"

base {
    archivesName = archivesBaseName
    version = libs.versions.mod.version.get()
    group = "dev.chicken"
}

repositories {
    mavenCentral()

    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    // Client-side animation runtime (GeckoLib)
    modImplementation(libs.geckolib)

    // NanoVG vector renderer, bundled into the mod (JiJ) with its natives
    implementation(libs.nanovg)
    include(libs.nanovg)

    for (classifier in listOf("natives-linux", "natives-windows", "natives-macos", "natives-macos-arm64")) {
        val native = variantOf(libs.nanovg) { classifier(classifier) }
        runtimeOnly(native)
        include(native)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to libs.versions.minecraft.get(),
            "jdk_version" to libs.versions.jdk.get(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}
