import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "winflasher.resources"
}

kotlin {
    mingwX64 {
        compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xklib-duplicated-unique-name-strategy=allow-first-with-warning")
            }
        }

        binaries.executable {
            entryPoint = "main"
            baseName = "MiuixWinSample"
            linkerOpts("-mwindows")
            freeCompilerArgs = listOf(
                "-Xbinary=gc=cms",
                "-Xbinary=gcSchedulerType=adaptive",
                "-Xklib-duplicated-unique-name-strategy=allow-first-with-warning",
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.blur)
        }
    }
}

val angleDllDir = layout.projectDirectory.dir("libs")
val toolDir = rootProject.layout.projectDirectory.dir("META-INF")
val firmwareDir = rootProject.layout.projectDirectory.dir("firmware-update")
val folkToolDir = rootProject.layout.projectDirectory.dir(".codex-cache/FolkTool")
val magiskPatcherDir = rootProject.layout.projectDirectory.dir(".codex-cache/MagiskPatcher")
val composeResourceDir = layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources")
val appIconPng = layout.projectDirectory.file("src/commonMain/composeResources/drawable/app_icon.png")
val appIconIco = layout.projectDirectory.file("src/commonMain/composeResources/drawable/app_icon.ico")
val embedExeIconScript = rootProject.layout.projectDirectory.file("tools/embed-exe-icon.ps1")

tasks.withType<KotlinNativeLink>().configureEach {
    doLast {
        val dllFiles = angleDllDir.asFile.listFiles()?.filter { it.extension.equals("dll", ignoreCase = true) } ?: emptyList()
        val outputDir = outputFile.get().parentFile
        val bundledAdb = outputDir.resolve("META-INF").resolve("adb.exe")
        if (bundledAdb.exists()) {
            runCatching {
                ProcessBuilder(bundledAdb.absolutePath, "kill-server")
                    .directory(outputDir)
                    .start()
                    .waitFor()
                Thread.sleep(300)
            }
        }
        dllFiles.forEach { dll ->
            dll.copyTo(outputDir.resolve(dll.name), overwrite = true)
        }
        if (toolDir.asFile.exists()) {
            delete(outputDir.resolve("META-INF"))
            copy {
                from(toolDir)
                into(outputDir.resolve("META-INF"))
            }
        }
        if (firmwareDir.asFile.exists()) {
            delete(outputDir.resolve("firmware-update"))
            copy {
                from(firmwareDir)
                into(outputDir.resolve("firmware-update"))
            }
        }
        val composeOutputDirs = listOf(
            outputDir.resolve("composeResources").resolve("winflasher.resources"),
            outputDir.resolve("bin").resolve("composeResources").resolve("winflasher.resources"),
            outputDir.resolve("META-INF").resolve("composeResources").resolve("winflasher.resources"),
        )
        val preparedComposeResources = composeResourceDir.get().asFile
        if (preparedComposeResources.exists()) {
            composeOutputDirs.forEach { composeOutputDir ->
                delete(composeOutputDir)
                copy {
                    from(preparedComposeResources)
                    into(composeOutputDir)
                }
            }
        }
        outputDir.resolve("bin").mkdirs()
        outputDir.resolve("META-INF").resolve("bin").mkdirs()
        listOf(appIconPng.asFile, appIconIco.asFile)
            .filter { it.exists() }
            .forEach { source ->
                source.copyTo(outputDir.resolve("bin").resolve(source.name), overwrite = true)
                source.copyTo(outputDir.resolve("META-INF").resolve("bin").resolve(source.name), overwrite = true)
            }
        if (appIconIco.asFile.exists() && embedExeIconScript.asFile.exists()) {
            val process = ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                embedExeIconScript.asFile.absolutePath,
                "-ExePath",
                outputFile.get().absolutePath,
                "-IconPath",
                appIconIco.asFile.absolutePath,
            )
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("Failed to embed executable icon, exit code: $exitCode")
            }
        }

        val folkToolOutputDir = outputDir.resolve("META-INF").resolve("tools").resolve("folktool")
        folkToolOutputDir.mkdirs()
        val folkToolBinaryDir = folkToolDir.file("kptools").asFile
        if (folkToolBinaryDir.exists()) {
            folkToolBinaryDir.copyRecursively(folkToolOutputDir, overwrite = true)
        }
        listOf(
            folkToolDir.file("assets/kpimg").asFile,
        ).filter { it.exists() }.forEach { source ->
            source.copyTo(folkToolOutputDir.resolve(source.name), overwrite = true)
        }

        val magiskToolOutputDir = outputDir.resolve("META-INF").resolve("tools").resolve("magiskpatcher")
        magiskToolOutputDir.mkdirs()
        listOf(
            magiskPatcherDir.file("publish/MagiskPatcher.exe").asFile,
            magiskPatcherDir.file("dependencies/7z.exe").asFile,
            magiskPatcherDir.file("dependencies/7z.dll").asFile,
            magiskPatcherDir.file("dependencies/magiskboot.exe").asFile,
            magiskPatcherDir.file("dependencies/MagiskPatcher.csv").asFile,
        ).filter { it.exists() }.forEach { source ->
            source.copyTo(magiskToolOutputDir.resolve(source.name), overwrite = true)
        }
    }
}
