plugins {
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

// `java-gradle-plugin` already puts gradleApi() on the `api` configuration.
// gradleApi() exposes the half-public/internal types this PoC relies on:
//   - org.gradle.api.internal.GradleInternal#baseProjectClassLoaderScope()
//   - org.gradle.api.internal.initialization.ClassLoaderScope#export(ClassPath)
//   - org.gradle.internal.classpath.DefaultClassPath
dependencies {
    // ASM is used to emit accessor bytecode directly (NO javac, NO extra project).
    implementation("org.ow2.asm:asm:9.7.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

gradlePlugin {
    plugins {
        create("buildParametersPoc") {
            id = "org.gradlex.buildparameters.poc"
            implementationClass = "org.gradlex.buildparameters.poc.BuildParametersSettingsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Surface the TestKit working dir output on failure.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
