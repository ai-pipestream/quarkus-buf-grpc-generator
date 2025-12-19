package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Generates Java code from proto files using buf generate.
 *
 * This task iterates over all exported module directories and runs
 * buf generate with the prepared buf.gen.yaml configuration.
 */
abstract class GenerateProtosTask extends DefaultTask {

    /**
     * Source mode: 'bsr', 'git', or 'git-proto-workspace'.
     *
     * <p>In 'git-proto-workspace' mode, this task runs a single {@code buf generate} against the workspace root,
     * using {@code --path} filters for only the registered modules.</p>
     */
    @Input
    abstract Property<String> getSourceMode()

    /**
     * Directory containing exported proto files (from fetchProtos).
     * Each subdirectory is a module.
     */
    @InputDirectory
    @SkipWhenEmpty
    abstract DirectoryProperty getExportDir()

    /**
     * The buf.gen.yaml configuration file (from prepareGenerators).
     */
    @InputFile
    abstract RegularFileProperty getBufGenYaml()

    /**
     * Output directory for generated Java sources.
     */
    @OutputDirectory
    abstract DirectoryProperty getOutputDir()

    /**
     * The buf executable (resolved from Maven Central).
     */
    @InputFiles
    abstract ConfigurableFileCollection getBufExecutable()

    /**
     * Module data captured during configuration phase for configuration cache compatibility.
     * Each entry is a map with: name, bsr (optional), gitRepo (optional), gitRef, gitSubdir
     *
     * <p>Used to determine {@code --path} filters in git-proto-workspace mode.</p>
     */
    @Input
    abstract ListProperty<Map<String, String>> getModuleData()

    /**
     * Extra arguments to pass to buf generate command.
     * <p>Example: <code>['--exclude-path', 'google/']</code></p>
     */
    @Input
    @org.gradle.api.tasks.Optional
    abstract ListProperty<String> getBufGenerateArgs()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        def exportDir = getExportDir().get().asFile
        def bufGenYaml = getBufGenYaml().get().asFile
        def outputDir = getOutputDir().get().asFile
        def extraArgs = getBufGenerateArgs().getOrElse([])
        def mode = getSourceMode().getOrElse('bsr')
        def moduleDataList = getModuleData().getOrElse([])

        // Ensure output directory exists
        outputDir.mkdirs()

        if (!exportDir.exists() || exportDir.listFiles()?.length == 0) {
            logger.warn("No exported protos found in ${exportDir}, skipping generation.")
            return
        }

        if (mode == 'git-proto-workspace') {
            // In workspace mode, exportDir is a checked-out workspace root.
            def modulePaths = resolveWorkspaceModulePaths(exportDir, moduleDataList)
            if (!modulePaths || modulePaths.isEmpty()) {
                throw new GradleException(
                    "git-proto-workspace mode could not determine any module paths for generation. " +
                        "Ensure the workspace contains a buf.yaml with a modules: list, or configure gitSubdir per module."
                )
            }

            def protoPaths = resolveWorkspaceProtoPaths(exportDir, modulePaths)
            if (!protoPaths || protoPaths.isEmpty()) {
                throw new GradleException(
                    "git-proto-workspace mode found 0 proto files under configured module paths: ${modulePaths.join(', ')}"
                )
            }

            logger.lifecycle("Generating code from workspace root for ${protoPaths.size()} proto file(s) across module(s): ${modulePaths.join(', ')}")
            this.generateWorkspace(exportDir, bufGenYaml, protoPaths, extraArgs)
            logger.lifecycle("Generated code to ${outputDir}")
            return
        }

        // Count modules for reporting
        def moduleCount = 0
        def failedModules = []

        exportDir.eachDir { moduleDir ->
            moduleCount++
            logger.lifecycle("Generating code for module: ${moduleDir.name}")

            try {
                this.generateModule(moduleDir, bufGenYaml, extraArgs)
            } catch (Exception e) {
                logger.error("Failed to generate code for module: ${moduleDir.name}", e)
                failedModules << moduleDir.name
            }
        }

        if (failedModules) {
            throw new GradleException(
                "Code generation failed for ${failedModules.size()} module(s): ${failedModules.join(', ')}"
            )
        }

        logger.lifecycle("Generated code for ${moduleCount} module(s) to ${outputDir}")
    }

    /**
     * Resolves the buf executable file, ensuring it's executable.
     */
    protected File resolveBufBinary() {
        def executable = getBufExecutable().singleFile
        if (!executable.canExecute()) {
            executable.setExecutable(true)
        }
        return executable
    }

    protected void generateModule(File moduleDir, File bufGenYaml, List<String> extraArgs) {
        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        // Build the command arguments
        def args = [bufBinary.absolutePath, 'generate', moduleDir.absolutePath, '--template', bufGenYaml.absolutePath]
        if (extraArgs && !extraArgs.isEmpty()) {
            args.addAll(extraArgs)
        }

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine args
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf generate failed for module '${moduleDir.name}'\n" +
                "Exit code: ${result.exitValue}\n" +
                "Check that proto files are valid and all dependencies are exported."
            )
        }
    }

    protected void generateWorkspace(File workspaceRoot, File bufGenYaml, List<String> protoPaths, List<String> extraArgs) {
        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        // Run from the workspace root so --path values are interpreted within the workspace context.
        def args = [bufBinary.absolutePath, 'generate', '.', '--template', bufGenYaml.absolutePath]
        protoPaths.each { p ->
            args.addAll(['--path', p])
        }
        if (extraArgs && !extraArgs.isEmpty()) {
            args.addAll(extraArgs)
        }

        ExecResult result = getExecOperations().exec { spec ->
            spec.workingDir = workspaceRoot
            spec.commandLine args
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf generate failed for workspace\n" +
                    "Exit code: ${result.exitValue}\n" +
                    "Check that proto files are valid and all workspace imports are resolvable."
            )
        }
    }

    /**
     * Resolve module paths for workspace mode.
     *
     * <p>Prefers paths declared in the workspace {@code buf.yaml} (modules: list). Falls back to {@code <gitSubdir>/proto}
     * or {@code <moduleName>/proto} if workspace config cannot be parsed.</p>
     */
    protected static List<String> resolveWorkspaceModulePaths(File workspaceRoot, List<Map<String, String>> moduleDataList) {
        if (!moduleDataList || moduleDataList.isEmpty()) {
            return []
        }

        def bufYaml = new File(workspaceRoot, 'buf.yaml')
        Map<String, String> workspaceModulePaths = [:]

        if (bufYaml.exists() && bufYaml.isFile()) {
            def modules = parseWorkspaceModules(bufYaml)
            modules.each { m ->
                def modulePath = m['path']
                def moduleNameFromPath = idFromModulePath(modulePath)
                if (moduleNameFromPath) {
                    workspaceModulePaths[moduleNameFromPath] = modulePath
                }
                def declaredName = m['name']
                if (declaredName) {
                    def nameId = declaredName.split('/').last()
                    workspaceModulePaths[nameId] = modulePath
                }
            }
        }

        def resolved = new LinkedHashSet<String>()
        moduleDataList.each { moduleData ->
            def moduleName = moduleData['name']
            def moduleSubdir = moduleData['gitSubdir'] ?: moduleName

            def candidate = workspaceModulePaths[moduleName] ?: workspaceModulePaths[moduleSubdir]
            if (candidate) {
                resolved.add(candidate)
                return
            }

            // Fall back to common conventions.
            def bySubdirProto = "${moduleSubdir}/proto"
            def byNameProto = "${moduleName}/proto"
            if (new File(workspaceRoot, bySubdirProto).exists()) {
                resolved.add(bySubdirProto)
            } else if (new File(workspaceRoot, byNameProto).exists()) {
                resolved.add(byNameProto)
            } else if (new File(workspaceRoot, moduleSubdir).exists()) {
                resolved.add(moduleSubdir)
            } else if (new File(workspaceRoot, moduleName).exists()) {
                resolved.add(moduleName)
            }
        }

        return resolved.toList()
    }

    /**
     * Converts workspace module roots (e.g. {@code common/proto}) into the list of proto file paths (relative to the
     * workspace root) to be passed to {@code buf generate --path}.
     *
     * <p>Buf does not allow specifying module roots as {@code --path}. Instead, we enumerate proto files beneath each
     * module root and pass their paths relative to the workspace root (e.g. {@code common/proto/ai/pipestream/.../foo.proto}).</p>
     */
    protected static List<String> resolveWorkspaceProtoPaths(File workspaceRoot, List<String> moduleRoots) {
        def result = new LinkedHashSet<String>()

        moduleRoots.each { moduleRoot ->
            def rootDir = new File(workspaceRoot, moduleRoot)
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                return
            }
            rootDir.eachFileRecurse { f ->
                if (!f.isFile() || !f.name.endsWith('.proto')) {
                    return
                }
                def relFromModuleRoot = rootDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                def relFromWorkspaceRoot = "${moduleRoot}/${relFromModuleRoot}".replace('\\', '/')
                result.add(relFromWorkspaceRoot)
            }
        }

        return result.toList().sort()
    }

    protected static List<Map<String, String>> parseWorkspaceModules(File bufYaml) {
        def modules = []
        def inModules = false
        Map<String, String> current = null

        bufYaml.readLines().each { line ->
            def trimmed = line.trim()
            if (trimmed.startsWith('#') || trimmed.isEmpty()) {
                return
            }
            if (trimmed == 'modules:' || trimmed.startsWith('modules:')) {
                inModules = true
                return
            }
            if (!inModules) {
                return
            }
            if (trimmed.startsWith('- ')) {
                if (current != null && current['path']) {
                    modules.add(current)
                }
                current = [:]
            }
            if (current == null) {
                return
            }
            def pathMatch = (trimmed =~ /^-?\s*path:\s*["']?([^"'\s]+)["']?\s*$/)
            if (pathMatch.find()) {
                current['path'] = pathMatch.group(1)
                return
            }
            def nameMatch = (trimmed =~ /^name:\s*["']?([^"'\s]+)["']?\s*$/)
            if (nameMatch.find()) {
                current['name'] = nameMatch.group(1)
            }
        }

        if (current != null && current['path']) {
            modules.add(current)
        }
        return modules
    }

    protected static String idFromModulePath(String modulePath) {
        if (!modulePath) {
            return null
        }
        def normalized = modulePath.replaceAll(/\/+$/, '')
        normalized = normalized.replaceAll(/\/proto$/, '')
        def parts = normalized.split('/')
        return parts ? parts.last() : null
    }
}
