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

import org.gradle.api.IsolatedAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradlex.buildparameters.poc.codegen.AsmAccessorGenerator;
import org.gradlex.buildparameters.poc.schema.BuildParametersSchema;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * A settings plugin that generates <em>type-safe</em> accessors for Gradle properties and makes them
 * available to every build script — without an extra Gradle project and without javac.
 *
 * <h2>How it works</h2>
 * <ol>
 *     <li>During settings evaluation it registers the {@code buildParameters { ... }} DSL, which captures
 *         the parameter <em>schema</em> (shape) — just like a version catalog is declared in settings.</li>
 *     <li>At {@code gradle.projectsLoaded} — the single lifecycle window where the base project
 *         {@link ClassLoaderScope} exists but is not yet locked (see
 *         {@code BuildTreePreparingProjectsPreparer}) — it emits the accessor classes as bytecode via ASM
 *         into a stable directory and {@code export()}s that directory into the base project scope so the
 *         classes are visible to every build script compiled afterwards. This step mutates only shared
 *         build-logic classloader state; it never touches a project.</li>
 *     <li>The typed {@code buildParameters} extension is registered per project from an
 *         {@linkplain org.gradle.api.IsolatedAction isolated} {@code gradle.lifecycle.beforeProject}
 *         action. The action captures only the generated class <em>name</em> (a {@link String}) and
 *         re-loads the class from each project's own scope, so it is safe under <b>Isolated Projects</b>
 *         (no captured {@code ClassLoader}, no cross-project access).</li>
 * </ol>
 *
 * <p>The values themselves flow through {@code providers.gradleProperty(...)} providers, so they are lazy
 * and Configuration Cache friendly; the generated accessor class is only needed at configuration time.</p>
 */
public class BuildParametersSettingsPlugin implements Plugin<Settings> {

    public static final String EXTENSION_NAME = "buildParameters";

    private static final String ROOT_FQCN =
        BuildParametersSchema.GENERATED_PACKAGE + "." + BuildParametersSchema.ROOT_SIMPLE_NAME;

    @Override
    public void apply(Settings settings) {
        // (1) Settings-time DSL: collect the schema. Populated by the settings script body, which runs
        // after this plugin is applied, so we must read it later (at projectsLoaded), not here.
        BuildParametersSchema schema = settings.getExtensions().create(EXTENSION_NAME, BuildParametersSchema.class);

        Gradle gradle = settings.getGradle();

        // (2) Generate + export the accessor classes. Shared build-logic state only — no project access.
        gradle.projectsLoaded(g -> generateAndExport(g, schema));

        // (3) Register the typed extension per project via an isolated action. It captures only the class
        // name; the ClassLoader and the generated class are resolved per project, inside the action, so
        // this is compatible with both the Configuration Cache and Isolated Projects.
        gradle.getLifecycle().beforeProject(new RegisterAccessorExtension(ROOT_FQCN));
    }

    private void generateAndExport(Gradle gradle, BuildParametersSchema schema) {
        if (schema.isEmpty()) {
            return;
        }

        // Emit bytecode (no javac) into a GLOBAL, content-addressed workspace under the Gradle user home —
        // NOT the project tree. This mirrors how Gradle generates version-catalog accessors and is what
        // keeps the Configuration Cache valid: the directory path is a pure function of the schema, the
        // bytes are deterministic, and we never probe the project's file system from build logic (such
        // probes are instrumented and an "absent -> created" flip would invalidate the cache entry).
        Map<String, byte[]> classes = new AsmAccessorGenerator().generate(schema);
        File classesDir = new File(
            gradle.getStartParameter().getGradleUserHomeDir(),
            "caches/build-parameters-poc/" + hash(classes) + "/classes");
        writeClasses(classes, classesDir);

        // Export into the base project class loader scope while it is still mutable.
        baseProjectClassLoaderScope(gradle).export(DefaultClassPath.of(classesDir));
    }

    /**
     * Isolated, per-project registration of the typed {@code buildParameters} extension.
     *
     * <p>Captures only the fully-qualified class name. The generated class is loaded from the project's own
     * {@link ClassLoaderScope} (an ancestor of which exported it in
     * {@link #generateAndExport}). If no parameters were declared the class will be absent and the action
     * silently does nothing.</p>
     */
    static final class RegisterAccessorExtension implements IsolatedAction<Project>, Serializable {
        private static final long serialVersionUID = 1L;

        private final String fqcn;

        RegisterAccessorExtension(String fqcn) {
            this.fqcn = fqcn;
        }

        @Override
        public void execute(Project project) {
            ClassLoader loader = ((ProjectInternal) project).getClassLoaderScope().getLocalClassLoader();
            Class<?> type;
            try {
                type = loader.loadClass(fqcn);
            } catch (ClassNotFoundException notGenerated) {
                return; // no build parameters declared -> nothing to register
            }
            Object accessor;
            try {
                accessor = type.getConstructor(ProviderFactory.class).newInstance(project.getProviders());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate generated accessor " + fqcn, e);
            }
            addExtension(project, type, accessor);
        }
    }

    // --- code generation + caching -----------------------------------------------------------------

    /**
     * Write the class files unconditionally. The target dir is content-addressed, so repeated writes (by
     * this or any other build sharing the Gradle user home) reproduce byte-identical content. We
     * deliberately avoid any {@code exists()}/{@code isDirectory()} probe: those are instrumented in build
     * logic and would register a file-system input that flips from absent to present between runs.
     */
    private void writeClasses(Map<String, byte[]> classes, File classesDir) {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            File target = new File(classesDir, entry.getKey().replace('.', '/') + ".class");
            //noinspection ResultOfMethodCallIgnored
            target.getParentFile().mkdirs();
            writeBytes(target, entry.getValue());
        }
    }

    private static String hash(Map<String, byte[]> classes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            classes.forEach((name, bytes) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            });
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- half-public API access --------------------------------------------------------------------

    /**
     * Obtain the base project {@link ClassLoaderScope}. This is the scope every project (and therefore every
     * build script) inherits from. At {@code projectsLoaded} it is created but not yet locked.
     *
     * <p>Accessed reflectively to tolerate the getter name across Gradle versions
     * ({@code baseProjectClassLoaderScope()} vs {@code getBaseProjectClassLoaderScope()}).</p>
     */
    private static ClassLoaderScope baseProjectClassLoaderScope(Gradle gradle) {
        GradleInternal gradleInternal = (GradleInternal) gradle;
        for (String name : new String[]{"baseProjectClassLoaderScope", "getBaseProjectClassLoaderScope"}) {
            try {
                Method method = GradleInternal.class.getMethod(name);
                return (ClassLoaderScope) method.invoke(gradleInternal);
            } catch (NoSuchMethodException ignored) {
                // try the next name
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access " + name + "()", e);
            }
        }
        throw new IllegalStateException("Could not locate baseProjectClassLoaderScope() on GradleInternal");
    }

    // --- small reflection / IO helpers -------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addExtension(Project project, Class<?> publicType, Object instance) {
        project.getExtensions().add((Class) publicType, EXTENSION_NAME, instance);
    }

    private static void writeBytes(File file, byte[] bytes) {
        try {
            Files.write(file.toPath(), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
