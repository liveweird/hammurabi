
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        constraints {
            // Keep the root plugin classpath above the FreeMarker path-traversal advisory floor.
            classpath("org.freemarker:freemarker:2.3.35")
        }
    }
}

// Keep resolved build, test, and runtime modules reproducible and visible to the
// vulnerability scanner. Refresh with `./gradlew dependencies --write-locks` for each project.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = org.gradle.api.artifacts.dsl.LockMode.STRICT
    }
}

subprojects {
    group = "ch.nokillswit"
    version = "1.0.0-SNAPSHOT"
}

// The settings plugins DSL resolves before project buildscript locking is available. Resolve
// the same marker here using the shared version property so its implementation enters the
// root lockfile and the vulnerability scan without adding it to the application classpath.
val settingsPluginAudit = configurations.create("settingsPluginAudit") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    kover(project(":server"))
    add(
        settingsPluginAudit.name,
        "org.gradle.toolchains.foojay-resolver-convention:org.gradle.toolchains.foojay-resolver-convention.gradle.plugin:${providers.gradleProperty("foojayResolverVersion").get()}",
    )
}

tasks.register("verifySettingsPluginAudit") {
    group = "verification"
    description = "Resolves the locked settings plugin graph before vulnerability scanning."
    doLast {
        settingsPluginAudit.resolve()
    }
}

// Resolves EVERY resolvable configuration of every project (and each buildscript classpath) down to its artifact files, so a
// missing or unreachable dependency THROWS here, under the strict locking and `--dependency-verification strict` the build
// already enforces, and the Gradle cache is warm for what follows. The `dependencies` / `buildEnvironment` report tasks cannot
// do this job: they print FAILED beside an unresolvable node and still exit 0. CI's `server` job runs this task behind
// `scripts/gradle-retry.sh` before the long `check` build (`.claude/docs/ci.md` "Transient resolution failures"). It adds no
// dependency and changes no lock state: it resolves exactly what is declared.
tasks.register("resolveAllDependencies") {
    group = "verification"
    description = "Resolves every resolvable configuration of every project to its files; fails on any miss."
    doLast {
        var resolved = 0
        allprojects.forEach { p ->
            (p.configurations + p.buildscript.configurations).filter { it.isCanBeResolved }.forEach { configuration ->
                configuration.incoming.files.files
                resolved++
            }
        }
        logger.lifecycle("Resolved $resolved configurations across ${allprojects.size} projects")
    }
}
