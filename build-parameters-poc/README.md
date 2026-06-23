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
8. **Cross-plugin composition** — a group type can be *owned by another artifact* (a different team's
   plugin) and mounted into your build parameters, with the generated accessor returning that team's
   published type (see below).

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

## Cross-plugin composition (one team authors a group, another uses it)

Yes — but the key constraint is that **the shared type identity must come from a published artifact, not
from per-build generation.** A generated class only exists inside the consumer's build, so another team
couldn't compile against it. So the engine generates only the *mount wiring*; the group's accessor type is
a normal published interface owned by the authoring team.

"Team A" ships (see `sample/teama/`): an interface `JavaDistribution`, a factory
`JavaDistributions.from(ProviderFactory, keyPrefix)`, a descriptor `JavaDistributions.javaDistribution()`,
and — crucially — its own build logic compiled against that interface (`JvmReport`). "Team B" mounts it:

```groovy
buildParameters {
    group('buildJvm', JavaDistributions.javaDistribution())   // team A's type
    group('testJvm',  JavaDistributions.javaDistribution())
}
```
```kotlin
val buildJvm: JavaDistribution = params.buildJvm   // accessor's type IS team A's published interface
JvmReport.describe(buildJvm)                        // team A's own logic consumes the same type
```

The generated `BuildParameters.getBuildJvm()` returns `JavaDistribution` and its body is just
`return JavaDistributions.from(providers, "buildJvm.")` — no class is generated for the external type.

**Why the classloaders line up.** The generated `BuildParameters` lives in the base project scope; team
A's classes live on the settings/plugin scope, which is an *ancestor* — so the generated code and every
build script resolve the same `JavaDistribution`. This is the same relationship the engine already relies
on to reference its own `Params` helper. In the PoC "team A" is a separate *package* in the plugin jar for
test convenience, but a separately-published jar works identically: it just has to be on the settings
classpath. That last step is verified independently — a jar compiled with plain `javac` and added via
`buildscript { dependencies { classpath files(...) } }` in `settings.gradle` is visible to `build.gradle.kts`.

Deployment options for team A's artifact, all equivalent in scope terms: ship it as a settings plugin the
consumer applies, add it via `pluginManagement`, or via the settings `buildscript` classpath. The one
thing you cannot do is have team A depend on a *generated* type — hence the published-interface model.

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
  schema/ExternalGroupType.java        # a group type owned by another artifact (cross-plugin)
  codegen/AsmAccessorGenerator.java    # ASM bytecode emitter (replaces javac)
  runtime/Params.java                  # serializable provider helpers called by generated bytecode
  sample/teama/                        # stand-in for a DIFFERENT team's published library
src/test/java/.../BuildParametersPluginFunctionalTest.java   # type-safety, CC, Isolated Projects, reuse, cross-team
```
