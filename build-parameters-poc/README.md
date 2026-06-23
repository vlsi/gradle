# build-parameters as a settings plugin — proof of concept

A runnable spike for [gradlex-org/build-parameters#243](https://github.com/gradlex-org/build-parameters/issues/243):
generate **type-safe Gradle property accessors** from a settings plugin, **without an extra Gradle
project and without javac**, and prove it survives the **Configuration Cache**.

```
buildParameters {                     // settings.gradle — declares the SHAPE (like a version catalog)
    string('greeting', 'hello')
    integer('count', 3)
    group('database') {
        it.string('url', 'jdbc:postgresql://localhost/db')
        it.integer('port', 5432)
    }
}
```
```kotlin
// build.gradle.kts — fully type-safe, checked at script-compile time
val params = the<BuildParameters>()
val port: Provider<Int> = params.database.port   // sourced from the `database.port` gradle property
```

## What this proves

Running `gradle test` (Gradle 8.14.3 here) green demonstrates, end to end:

1. **Type safety** — the Kotlin build script imports the generated `BuildParameters` type and calls
   `params.greeting` / `params.database.port`. If the class or its typed getters were missing, the script
   would fail to *compile*. `javap` confirms the bytecode carries generic signatures
   (`Provider<String> getGreeting()`, `Provider<Integer> getCount()`).
2. **No javac, no extra project** — accessor classes are emitted as bytecode with ASM during the main
   build's init phase (`AsmAccessorGenerator`).
3. **Nested groups** — `params.database.port` resolves through a generated `Database` class.
4. **Values from gradle properties**, overridable on the command line (`-Pdatabase.port=6543`).
5. **Configuration Cache round-trip** — first run stores the entry, second run reports
   *"Reusing configuration cache"* and re-executes the task from the cached graph.
6. **Isolated Projects** — a multi-project build (root + subproject) configured with
   `org.gradle.unsafe.isolated-projects=true` and `--configuration-cache-problems=fail` runs green, so
   the per-project registration produces zero IP violations.
7. **Reusable group types** — a `groupType` can be factored out and mounted in several places, generating
   a single shared accessor type (see below).

## How it works

The issue's blocker is real: **Gradle exposes no hook to generate/compile code during settings
evaluation.** A settings plugin's `apply()` runs *after* the settings `ClassLoaderScope` is already
locked (`DefaultPluginRequestApplicator.applyPlugins` locks it before invoking plugins), so you cannot
`export()` into a scope that build scripts inherit from at that point.

The one usable window is **`gradle.projectsLoaded`**. It fires inside `BuildTreePreparingProjectsPreparer`
*after* the base project `ClassLoaderScope` is created (the same scope Gradle uses to export the
`libs.*` version-catalog accessors) but *before* that scope is locked and *before* any build script is
compiled. So the plugin:

1. registers the `buildParameters { }` DSL during `apply()` to capture the schema;
2. at `projectsLoaded`, **generates + exports** (shared build-logic state only, no project access):
   - emits accessor bytecode (ASM) into a **global, content-addressed** workspace,
   - `baseProjectClassLoaderScope().export(classesDir)` — now visible to every build script;
3. registers the typed `buildParameters` extension **per project** from an isolated
   `gradle.lifecycle.beforeProject` action — see below.

### Per-project registration: Configuration Cache *and* Isolated Projects safe

The extension is added by a `gradle.getLifecycle().beforeProject(IsolatedAction)` that captures only the
generated class **name** (a `String`) and, inside the action, re-loads that class from each project's own
`ClassLoaderScope` (`((ProjectInternal) project).getClassLoaderScope().getLocalClassLoader()`). Because it
captures no `ClassLoader` and never touches another project, it is isolatable — which is exactly what
Isolated Projects requires. The earlier, simpler approach (iterating `gradle.getRootProject()
.getAllprojects()` at `projectsLoaded`) works for the plain Configuration Cache but is cross-project
access and would violate IP; the isolated action is the IP-safe replacement.

Values flow through `providers.gradleProperty(...)` with **named, `Serializable`** transformers
(`Params.StringToInteger`, …) rather than lambdas, so the resulting `Provider`s serialize cleanly into the
Configuration Cache. The generated class is only needed at configuration time; on a CC hit nothing here
re-runs.

## Reusable group types (DSL extensibility)

The DSL is extensible in two senses:

- **Authoring reuse** is free — the settings DSL is just code, so the body of a group can be a function
  applied in several `group { }` blocks. (But that alone still generates a *distinct* type per mount.)
- **Type reuse** — factor a group out as a named `groupType` and mount it wherever you like; all mounts
  share one generated accessor type:

  ```groovy
  buildParameters {
      def javaDistribution = groupType('JavaDistribution') {
          it.string('version', '17')
          it.string('vendor', 'adoptium')
      }
      group('buildJvm', javaDistribution)
      group('testJvm', javaDistribution)
  }
  ```
  ```kotlin
  // One helper for both mounts — compiles only because they share the JavaDistribution type:
  fun coordinates(jvm: JavaDistribution) = jvm.version.zip(jvm.vendor) { v, vendor -> "$vendor@$v" }
  coordinates(params.buildJvm)   // reads buildJvm.version / buildJvm.vendor
  coordinates(params.testJvm)    // reads testJvm.version  / testJvm.vendor
  ```

This works because generated group classes are **prefix-parameterized**: each carries the dotted key
prefix of its mount point (`buildJvm.`, `testJvm.`) and resolves `prefix + key` at runtime, so one class
serves every mount. `javap` confirms exactly two classes are generated — `BuildParameters` and a single
`JavaDistribution` — and the root's `getBuildJvm()`/`getTestJvm()` both return that one type. The schema
model (`BuildParametersSchema`) separates a group **type** (`GroupType`, one generated class) from its
**mounts** (`Mount`); the generator dedups types and errors clearly if two distinct groups would collide
on a class name. (Per-mount default overrides are not implemented — a reusable type's defaults are shared.)

## The Configuration Cache finding (the interesting part)

The first attempt wrote the classes into a **project-relative** `build/…` dir and probed it with
`File.isDirectory()`. Both are problems: build-logic file access is *instrumented*, so the
"absent → created" flip registered as a changed input and **invalidated the cache** on the second run:

```
configuration cache cannot be reused because the file system entry
'build/build-parameters-accessors/classes' has been created.
```

The fix mirrors what Gradle core does for version catalogs: write to a **content-addressed directory
under `GRADLE_USER_HOME`** (a pure function of the schema, byte-deterministic) and **never probe the
file system from build logic**. With that, the CC entry is reused. This is the single most important
constraint for a production implementation.

## Half-public / internal APIs used

| API | Why | Stability |
|---|---|---|
| `GradleInternal.baseProjectClassLoaderScope()` | the scope build scripts inherit; accessed reflectively to tolerate the getter name across versions | internal |
| `ClassLoaderScope.export(ClassPath)` | publish generated classes to that scope | internal |
| `DefaultClassPath.of(File)` | wrap the classes dir | internal |
| `Gradle.projectsLoaded(Action)` | the only un-locked-scope window | **public** |
| `Gradle.getLifecycle().beforeProject(IsolatedAction)` | IP-safe per-project registration | **public** (incubating) |
| `ProjectInternal.getClassLoaderScope()` | load the generated class from the project's own scope, inside the isolated action | internal |
| `providers.gradleProperty(...)`, `ExtensionContainer.add(publicType, name, value)` | values + typed extension | **public** |

## Known limitations / what a production version needs

- **Caching the generation** should use the `ExecutionEngine` + an `ImmutableWorkspaceProvider` (as
  `DefaultDependenciesAccessors` does) instead of the hand-rolled content-addressed dir here.
- **IDE import.** Surfacing the generated classpath to the Kotlin DSL script-model builder for IDE sync
  is not addressed here.
- **Env vars, lists, required/optional, descriptions** — only `string`/`integer`/`bool` with optional
  defaults are implemented.
- The internal-API dependence is the reason the issue ultimately asks Gradle for a *public* hook; this
  spike shows the behavior is achievable today and what such a hook would need to expose.

## Run it

Requires a JDK and Gradle 8.14.3+ on `PATH` (this spike was validated with Gradle 8.14.3, JDK 21).

```bash
gradle build      # compiles the plugin (ASM, no javac for the accessors) and runs the functional tests
gradle test       # just the TestKit proof, incl. the CC store -> reuse round-trip
```

## Layout

```
src/main/java/org/gradlex/buildparameters/poc/
  BuildParametersSettingsPlugin.java   # the settings plugin: projectsLoaded export + typed extension
  schema/BuildParametersSchema.java    # the buildParameters { } DSL + schema model
  codegen/AsmAccessorGenerator.java    # ASM bytecode emitter (replaces javac)
  runtime/Params.java                  # serializable provider helpers called by generated bytecode
src/test/java/.../BuildParametersPluginFunctionalTest.java   # type-safety + CC round-trip + Isolated Projects proof
```
