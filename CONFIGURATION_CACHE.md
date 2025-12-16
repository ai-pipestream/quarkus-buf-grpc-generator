# Configuration Cache Compatibility

This document describes the configuration cache compatibility fixes implemented in the proto-toolchain plugin and best practices for maintaining compatibility.

## Overview

Gradle's [Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html) is a feature that caches the result of the configuration phase, significantly speeding up builds. However, it requires tasks to be written in a specific way that avoids capturing non-serializable objects (like `Project` instances) during the execution phase.

The proto-toolchain plugin was updated in version 0.7.3-SNAPSHOT to be fully compatible with Gradle 9+ configuration cache requirements.

## Issues Fixed

### 1. PrepareGeneratorsTask: Project Object Access at Execution Time

**Problem:**
The task was accessing the `project` object during execution, which is incompatible with configuration cache:

```groovy
// ❌ BAD: Accessing project at execution time
def config = project.configurations.detachedConfiguration(dependency)
def files = project.file(pluginOut).absolutePath
```

**Error:**
```
Invocation of 'Task.project' by task ':prepareGenerators' at execution time is unsupported with the configuration cache.
```

**Solution:**
- **Injected Services**: Use `@Inject` to get `ConfigurationContainer` and `DependencyHandler` instead of accessing via `project`
- **Pre-resolved Dependencies**: Resolve dependencies during configuration phase in `afterEvaluate` block
- **Captured Properties**: Store project directory path as an `@Input` property during configuration phase

```groovy
// ✅ GOOD: Inject services and capture values during configuration
@Inject
protected abstract ConfigurationContainer getConfigurations()

@Inject
protected abstract DependencyHandler getDependencies()

@Input
abstract Property<String> getProjectDir()

// In plugin:
task.projectDir.set(project.layout.projectDirectory.asFile.absolutePath)

// Pre-resolve during configuration phase:
project.afterEvaluate {
    def mutinyGeneratorConfig = project.configurations.detachedConfiguration(
        project.dependencies.create("io.quarkus:quarkus-grpc-protoc-plugin:${version}")
    )
    prepareTask.configure { task ->
        task.mutinyGeneratorJars.setFrom(mutinyGeneratorConfig)
    }
}
```

### 2. PrepareGeneratorsTask: Unsafe Configuration Resolution

**Problem:**
Resolving detached configurations at execution time violates Gradle 9+ safety requirements:

```groovy
// ❌ BAD: Resolving configuration at execution time
def config = getConfigurations().detachedConfiguration(dependency)
def files = config.resolve()  // Requires exclusive lock
```

**Error:**
```
Resolution of the configuration ':detachedConfiguration1' was attempted without an exclusive lock. 
This is unsafe and not allowed.
```

**Solution:**
Pre-resolve the configuration during the configuration phase and pass resolved files as `@InputFiles`:

```groovy
// ✅ GOOD: Resolve during configuration phase
@InputFiles
@org.gradle.api.tasks.Optional
abstract ConfigurableFileCollection getMutinyGeneratorJars()

// In plugin (during configuration phase):
def mutinyGeneratorConfig = project.configurations.detachedConfiguration(...)
task.mutinyGeneratorJars.setFrom(mutinyGeneratorConfig)

// In task (at execution time):
def files = getMutinyGeneratorJars().files  // Already resolved
```

### 3. BuildDescriptorsTask: Extension Reference in onlyIf Closure

**Problem:**
The `onlyIf` closure was capturing the extension object, which contains a project reference:

```groovy
// ❌ BAD: Capturing extension (which has project reference)
task.onlyIf {
    extension.generateDescriptors.get()  // Extension holds project reference
}
```

**Error:**
```
cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject'
```

**Solution:**
Evaluate the boolean value during configuration phase and use `task.enabled` instead:

```groovy
// ✅ GOOD: Capture value during configuration phase
def shouldGenerateDescriptors = extension.generateDescriptors.getOrElse(true)
task.enabled = shouldGenerateDescriptors
```

## Key Principles

### 1. No Project Access at Execution Time

**Never:**
- Access `project` object in `@TaskAction` methods
- Use `project.file()`, `project.configurations`, `project.dependencies` at execution time
- Capture closures that reference `project`

**Instead:**
- Inject services: `@Inject abstract ConfigurationContainer getConfigurations()`
- Capture values during configuration: `task.projectDir.set(...)`
- Use `@Input` properties for paths and values

### 2. Resolve Dependencies During Configuration Phase

**Never:**
- Call `config.resolve()` or access `config.files` at execution time
- Create detached configurations in `@TaskAction`

**Instead:**
- Create and resolve configurations in plugin's configuration phase
- Use `afterEvaluate` if you need property values
- Pass resolved files as `@InputFiles` to tasks

### 3. Avoid Capturing Non-Serializable Objects

**Never:**
- Capture `Project`, `Extension`, or other Gradle model objects in closures
- Store references to non-serializable objects in task state

**Instead:**
- Extract primitive values or strings during configuration
- Use `Property<T>` types for configuration values
- Mark non-serializable fields as `@Internal` if absolutely necessary

## Implementation Details

### PrepareGeneratorsTask Changes

**Before:**
```groovy
@TaskAction
void prepare() {
    // ❌ Accessing project at execution time
    def dependency = project.dependencies.create(...)
    def config = project.configurations.detachedConfiguration(dependency)
    def files = config.resolve()
    
    // ❌ Using project.file() at execution time
    def pluginOut = project.file(plugin.out.get()).absolutePath
}
```

**After:**
```groovy
// ✅ Inject services
@Inject
protected abstract ConfigurationContainer getConfigurations()

@Inject
protected abstract DependencyHandler getDependencies()

// ✅ Capture project directory during configuration
@Input
abstract Property<String> getProjectDir()

// ✅ Pre-resolved files as input
@InputFiles
abstract ConfigurableFileCollection getMutinyGeneratorJars()

@TaskAction
void prepare() {
    // ✅ Use pre-resolved files
    def files = getMutinyGeneratorJars().files
    
    // ✅ Use captured project directory
    def pluginOut = plugin.out.get()
    if (!pluginOut.startsWith('/')) {
        def projectDir = new File(getProjectDir().get())
        pluginOut = new File(projectDir, pluginOut).absolutePath
    }
}
```

### ProtoToolchainPlugin Changes

**Before:**
```groovy
def prepareTask = project.tasks.register("prepareGenerators", PrepareGeneratorsTask) { task ->
    // Task configuration
}
```

**After:**
```groovy
def prepareTask = project.tasks.register("prepareGenerators", PrepareGeneratorsTask) { task ->
    // ✅ Capture project directory during configuration
    task.projectDir.set(project.layout.projectDirectory.asFile.absolutePath)
    // ... other configuration
}

// ✅ Pre-resolve dependencies in afterEvaluate
project.afterEvaluate {
    def mutinyGeneratorConfig = project.configurations.detachedConfiguration(
        project.dependencies.create("io.quarkus:quarkus-grpc-protoc-plugin:${extension.quarkusGrpcVersion.get()}")
    )
    prepareTask.configure { task ->
        task.mutinyGeneratorJars.setFrom(mutinyGeneratorConfig)
    }
}
```

### BuildDescriptorsTask Changes

**Before:**
```groovy
def buildDescriptorsTask = project.tasks.register("buildDescriptors", BuildDescriptorsTask) { task ->
    task.onlyIf {
        extension.generateDescriptors.get()  // ❌ Captures extension
    }
}
```

**After:**
```groovy
// ✅ Evaluate during configuration phase
def shouldGenerateDescriptors = extension.generateDescriptors.getOrElse(true)
def buildDescriptorsTask = project.tasks.register("buildDescriptors", BuildDescriptorsTask) { task ->
    task.enabled = shouldGenerateDescriptors  // ✅ Use boolean value
}
```

## Testing Configuration Cache Compatibility

To verify configuration cache compatibility:

1. **Enable configuration cache:**
   ```properties
   # gradle.properties
   org.gradle.configuration-cache=true
   ```

2. **Run build twice:**
   ```bash
   ./gradlew clean build
   ./gradlew build  # Second run should reuse cache
   ```

3. **Check for warnings:**
   - Look for "Configuration cache problems found" in build output
   - Review `build/reports/configuration-cache/` for detailed reports

4. **Verify cache reuse:**
   - Second build should show "Reusing configuration cache"
   - Build should be significantly faster

## Best Practices for Plugin Developers

1. **Use Injected Services**: Always inject `ConfigurationContainer`, `DependencyHandler`, etc., instead of accessing via `project`

2. **Capture Early**: Extract values during configuration phase, not execution phase

3. **Pre-resolve Dependencies**: Create and resolve configurations in plugin configuration, pass as `@InputFiles`

4. **Use Properties**: Prefer `Property<T>` types over direct values for configuration

5. **Mark Appropriately**: Use `@Input`, `@InputFiles`, `@OutputDirectory`, etc., correctly

6. **Test with Cache**: Always test with configuration cache enabled in CI

7. **Avoid Closures**: Minimize closure usage that captures non-serializable objects

## References

- [Gradle Configuration Cache Documentation](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Gradle Configuration Cache Requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)
- [Gradle Task Implementation Best Practices](https://docs.gradle.org/current/userguide/custom_tasks.html)

## Version History

- **0.7.3-SNAPSHOT**: Initial configuration cache compatibility fixes
  - Fixed `PrepareGeneratorsTask` project access
  - Fixed `BuildDescriptorsTask` extension capture
  - Pre-resolved Mutiny generator dependencies
