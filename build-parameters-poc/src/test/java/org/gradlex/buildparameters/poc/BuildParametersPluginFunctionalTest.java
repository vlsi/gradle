/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradlex.buildparameters.poc;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildParametersPluginFunctionalTest {

    @TempDir
    Path projectDir;

    /**
     * The settings script declares the schema (Groovy DSL). Nested groups use {@code it.} because the
     * schema methods take {@code Action} and Groovy does not rebind the closure delegate for SAM coercion.
     */
    private static final String SETTINGS = String.join("\n",
        "plugins {",
        "    id 'org.gradlex.buildparameters.poc'",
        "}",
        "",
        "buildParameters {",
        "    string('greeting', 'hello')",
        "    integer('count', 3)",
        "    bool('verbose', false)",
        "    group('database') {",
        "        it.string('url', 'jdbc:postgresql://localhost/db')",
        "        it.integer('port', 5432)",
        "    }",
        "}",
        "",
        "rootProject.name = 'consumer'",
        "");

    /**
     * The build script is Kotlin DSL and references the generated accessor type by name. If the accessor
     * class were not on the build-script compile classpath (or lacked these exact typed getters), this
     * script would fail to COMPILE — so a green build is itself the type-safety proof.
     */
    private static final String BUILD = String.join("\n",
        "import org.gradlex.buildparameters.generated.BuildParameters",
        "",
        "val params = the<BuildParameters>()",
        "",
        "tasks.register(\"printParams\") {",
        "    val greeting = params.greeting          // Provider<String>",
        "    val count = params.count                // Provider<Int>",
        "    val dbUrl = params.database.url          // nested, Provider<String>",
        "    val dbPort = params.database.port        // nested, Provider<Int>",
        "    inputs.property(\"greeting\", greeting)",
        "    inputs.property(\"count\", count)",
        "    inputs.property(\"dbUrl\", dbUrl)",
        "    inputs.property(\"dbPort\", dbPort)",
        "    doLast {",
        "        println(\"BP greeting=\" + greeting.get())",
        "        println(\"BP count=\" + count.get())",
        "        println(\"BP dbUrl=\" + dbUrl.get())",
        "        println(\"BP dbPort=\" + dbPort.get())",
        "    }",
        "}",
        "");

    // --- Reusable group types -----------------------------------------------------------------------

    private static final String REUSE_SETTINGS = String.join("\n",
        "plugins {",
        "    id 'org.gradlex.buildparameters.poc'",
        "}",
        "",
        "buildParameters {",
        "    def javaDistribution = groupType('JavaDistribution') {",
        "        it.string('version', '17')",
        "        it.string('vendor', 'adoptium')",
        "    }",
        "    group('buildJvm', javaDistribution)",
        "    group('testJvm', javaDistribution)",
        "}",
        "",
        "rootProject.name = 'consumer'",
        "");

    private static final String REUSE_BUILD = String.join("\n",
        "import org.gradle.api.provider.Provider",
        "import org.gradlex.buildparameters.generated.BuildParameters",
        "import org.gradlex.buildparameters.generated.JavaDistribution",
        "",
        "val params = the<BuildParameters>()",
        "",
        "// A single helper applied to BOTH mounts. This only compiles if buildJvm and testJvm share the",
        "// generated JavaDistribution type -- i.e. it is the proof that the group type is reused.",
        "fun coordinates(jvm: JavaDistribution): Provider<String> =",
        "    jvm.version.zip(jvm.vendor) { version, vendor -> \"$vendor@$version\" }",
        "",
        "tasks.register(\"printJvms\") {",
        "    val build = coordinates(params.buildJvm)",
        "    val test = coordinates(params.testJvm)",
        "    inputs.property(\"build\", build)",
        "    inputs.property(\"test\", test)",
        "    doLast {",
        "        println(\"buildJvm=\" + build.get())",
        "        println(\"testJvm=\" + test.get())",
        "    }",
        "}",
        "");

    /**
     * Factor a "java distribution" group out as a reusable {@code groupType} and mount it at both
     * {@code buildJvm} and {@code testJvm}. Both accessors are the same generated type (the shared
     * {@code coordinates(JavaDistribution)} helper compiles), yet each reads its own prefixed properties
     * ({@code buildJvm.version} vs {@code testJvm.version}).
     */
    @Test
    void reusableGroupTypeIsSharedAcrossMounts() throws IOException {
        write("settings.gradle", REUSE_SETTINGS);
        write("build.gradle.kts", REUSE_BUILD);

        BuildResult result = run("printJvms", "-PtestJvm.version=21");

        assertContains(result.getOutput(), "buildJvm=adoptium@17"); // both defaults
        assertContains(result.getOutput(), "testJvm=adoptium@21");  // version overridden per mount
    }

    // --- Isolated Projects (multi-project) ----------------------------------------------------------

    private static final String IP_SETTINGS = String.join("\n",
        "plugins {",
        "    id 'org.gradlex.buildparameters.poc'",
        "}",
        "",
        "buildParameters {",
        "    string('greeting', 'hello')",
        "    group('database') {",
        "        it.integer('port', 5432)",
        "    }",
        "}",
        "",
        "rootProject.name = 'consumer'",
        "include 'app'",
        "");

    private static final String IP_ROOT_BUILD = String.join("\n",
        "import org.gradlex.buildparameters.generated.BuildParameters",
        "val params = the<BuildParameters>()",
        "tasks.register(\"printParams\") {",
        "    val greeting = params.greeting",
        "    inputs.property(\"greeting\", greeting)",
        "    doLast { println(\"ROOT greeting=\" + greeting.get()) }",
        "}",
        "");

    private static final String IP_APP_BUILD = String.join("\n",
        "import org.gradlex.buildparameters.generated.BuildParameters",
        "val params = the<BuildParameters>()",
        "tasks.register(\"printParams\") {",
        "    val dbPort = params.database.port",
        "    inputs.property(\"dbPort\", dbPort)",
        "    doLast { println(\"APP dbPort=\" + dbPort.get()) }",
        "}",
        "");

    /**
     * Isolated Projects is stricter than the Configuration Cache: each project is configured in isolation
     * and cross-project access is forbidden. {@code --configuration-cache-problems=fail} makes any
     * violation fail the build, so a green run here proves the per-project {@code beforeProject} isolated
     * action registers the typed accessor without any IP problem — in both the root and the subproject.
     */
    @Test
    void worksUnderIsolatedProjectsInAMultiProjectBuild() throws IOException {
        write("settings.gradle", IP_SETTINGS);
        write("gradle.properties", "org.gradle.unsafe.isolated-projects=true\n");
        write("build.gradle.kts", IP_ROOT_BUILD);
        write("app/build.gradle.kts", IP_APP_BUILD);

        BuildResult first = run("printParams", "--configuration-cache-problems=fail");
        assertContains(first.getOutput(), "Isolated projects is an incubating feature");
        assertContains(first.getOutput(), "ROOT greeting=hello");
        assertContains(first.getOutput(), "APP dbPort=5432");
        assertContains(first.getOutput(), "Configuration cache entry stored");

        BuildResult second = run("printParams", "--configuration-cache-problems=fail");
        assertContains(second.getOutput(), "Reusing configuration cache");
        assertContains(second.getOutput(), "ROOT greeting=hello");
        assertContains(second.getOutput(), "APP dbPort=5432");
    }

    @Test
    void typeSafeAccessorsSurviveConfigurationCacheRoundTrip() throws IOException {
        writeProject();

        // First run: configuration cache MISS -> settings + projectsLoaded run, accessors generated/exported.
        BuildResult first = run("printParams", "--configuration-cache");
        assertContains(first.getOutput(), "BP greeting=hello");
        assertContains(first.getOutput(), "BP count=3");
        assertContains(first.getOutput(), "BP dbUrl=jdbc:postgresql://localhost/db");
        assertContains(first.getOutput(), "BP dbPort=5432");
        assertContains(first.getOutput(), "Configuration cache entry stored");

        // Second run: configuration cache HIT -> configuration phase (incl. our projectsLoaded hook) is
        // skipped entirely. The task still executes from the cached graph and the serialized providers
        // re-read the gradle properties. This proves the wiring is CC-compatible.
        BuildResult second = run("printParams", "--configuration-cache");
        assertContains(second.getOutput(), "Reusing configuration cache");
        assertContains(second.getOutput(), "BP greeting=hello");
        assertContains(second.getOutput(), "BP count=3");
        assertContains(second.getOutput(), "BP dbPort=5432");
    }

    @Test
    void valuesComeFromGradlePropertiesAndAreOverridable() throws IOException {
        writeProject();

        BuildResult result = run(
            "printParams",
            "--configuration-cache",
            "-Pgreeting=ciao",
            "-Pcount=7",
            "-Pdatabase.port=6543");

        assertContains(result.getOutput(), "BP greeting=ciao");
        assertContains(result.getOutput(), "BP count=7");
        assertContains(result.getOutput(), "BP dbPort=6543");
        // url keeps its default
        assertContains(result.getOutput(), "BP dbUrl=jdbc:postgresql://localhost/db");
    }

    private void writeProject() throws IOException {
        write("settings.gradle", SETTINGS);
        write("build.gradle.kts", BUILD);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private BuildResult run(String... args) {
        String[] withCommon = new String[args.length + 2];
        System.arraycopy(args, 0, withCommon, 0, args.length);
        withCommon[args.length] = "--stacktrace";
        // Surface any deprecation coming from the plugin mechanism itself (there should be none).
        withCommon[args.length + 1] = "--warning-mode=all";
        return GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(withCommon)
            .forwardOutput()
            .build();
    }

    private static void assertContains(String output, String needle) {
        assertTrue(output.contains(needle), () -> "Expected build output to contain:\n  " + needle + "\nbut was:\n" + output);
    }
}
