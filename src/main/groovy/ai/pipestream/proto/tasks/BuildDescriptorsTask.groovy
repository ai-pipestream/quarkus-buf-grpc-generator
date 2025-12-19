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
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Builds protobuf descriptor files using buf build.
 *
 * <p>This task creates a <code>FileDescriptorSet</code> (<code>.desc</code> file) that can be used
 * for reflection, dynamic message handling, or documentation generation.</p>
 *
 * <p>For multi-module projects, this task creates a flat directory containing
 * all unique proto files (deduplicated by path), then builds a single
 * combined descriptor that includes all services and messages.</p>
 */
abstract class BuildDescriptorsTask extends DefaultTask {

    /**
     * Source mode: 'bsr', 'git', or 'git-proto-workspace'.
     *
     * <p>In 'git-proto-workspace' mode, this task runs a single {@code buf build} against the workspace root,
     * using {@code --path} filters for only the registered modules.</p>
     */
    @Input
    abstract Property<String> getSourceMode()

    /**
     * Directory containing exported proto files (from <code>fetchProtos</code>).
     *
     * <p>Each subdirectory is a module.</p>
     */
    @InputDirectory
    @SkipWhenEmpty
    abstract DirectoryProperty getExportDir()

    /**
     * Output file for the descriptor set.
     */
    @OutputFile
    abstract RegularFileProperty getDescriptorPath()

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

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void build() {
        def exportDir = getExportDir().get().asFile
        def descriptorFile = getDescriptorPath().get().asFile
        def mode = getSourceMode().getOrElse('bsr')
        def moduleDataList = getModuleData().getOrElse([])

        // Ensure output directory exists
        descriptorFile.parentFile.mkdirs()

        if (!exportDir.exists() || exportDir.listFiles()?.length == 0) {
            logger.warn("No exported protos found in ${exportDir}, skipping descriptor build.")
            return
        }

        if (mode == 'git-proto-workspace') {
            def modulePaths = resolveWorkspaceModulePaths(exportDir, moduleDataList)
            if (!modulePaths || modulePaths.isEmpty()) {
                throw new GradleException(
                    "git-proto-workspace mode could not determine any module paths for descriptor build. " +
                        "Ensure the workspace contains a buf.yaml with a modules: list, or configure gitSubdir per module."
                )
            }

            def protoPaths = resolveWorkspaceProtoPaths(exportDir, modulePaths)
            if (!protoPaths || protoPaths.isEmpty()) {
                throw new GradleException(
                    "git-proto-workspace mode found 0 proto files under configured module paths: ${modulePaths.join(', ')}"
                )
            }

            logger.lifecycle("Building descriptors from workspace root for ${protoPaths.size()} proto file(s) across module(s): ${modulePaths.join(', ')}")
            this.buildWorkspaceDescriptor(exportDir, descriptorFile, protoPaths)
            logger.lifecycle("Built descriptor file: ${descriptorFile}")
            return
        }

        // Collect all module directories
        List<File> moduleDirs = []
        exportDir.eachDir { File moduleDir ->
            moduleDirs << moduleDir
        }

        if (moduleDirs.isEmpty()) {
            logger.warn("No module directories found in ${exportDir}, skipping descriptor build.")
            return
        }

        logger.lifecycle("Building descriptors for ${moduleDirs.size()} module(s)")

        // For single module, build directly
        if (moduleDirs.size() == 1) {
            this.buildDescriptor(moduleDirs[0], descriptorFile)
        } else {
            // Multiple modules - create a flat directory with all unique protos
            def flatDir = new File(descriptorFile.parentFile, "flat-protos")
            if (flatDir.exists()) {
                flatDir.deleteDir()
            }
            flatDir.mkdirs()

            // Copy all proto files from all modules to flat directory
            // Files are deduplicated by their relative path (later modules overwrite earlier ones)
            def protoCount = 0
            moduleDirs.each { File moduleDir ->
                copyProtosToFlat(moduleDir, flatDir)
            }

            // Count proto files
            flatDir.eachFileRecurse { file ->
                if (file.name.endsWith('.proto')) {
                    protoCount++
                }
            }
            logger.lifecycle("Created flat directory with ${protoCount} unique proto files")

            // Build combined descriptor from flat directory
            this.buildDescriptor(flatDir, descriptorFile)

            // Clean up flat directory
            flatDir.deleteDir()
        }

        logger.lifecycle("Built descriptor file: ${descriptorFile}")
    }

    /**
     * Recursively copies all proto files from sourceDir to targetDir,
     * preserving directory structure. Files with the same relative path
     * are deduplicated (later copies overwrite earlier ones).
     */
    protected static void copyProtosToFlat(File sourceDir, File targetDir) {
        sourceDir.eachFileRecurse { file ->
            if (file.isFile()) {
                // Calculate relative path from sourceDir
                def relativePath = sourceDir.toPath().relativize(file.toPath()).toString()
                def targetFile = new File(targetDir, relativePath)

                // Create parent directories if needed
                targetFile.parentFile.mkdirs()

                // Copy file (overwrites if exists - deduplication)
                Files.copy(file.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
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

    protected void buildDescriptor(File moduleDir, File outputFile) {
        logger.lifecycle("Building descriptor for: ${moduleDir.name}")

        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        ExecResult result = getExecOperations().exec { spec ->
            // buf build outputs a Buf Image which is compatible with FileDescriptorSet
            spec.commandLine bufBinary.absolutePath, 'build', moduleDir.absolutePath, '-o', outputFile.absolutePath
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf build failed for '${moduleDir.name}'\n" +
                "Exit code: ${result.exitValue}\n" +
                "Check that proto files are valid."
            )
        }
    }

    protected void buildWorkspaceDescriptor(File workspaceRoot, File outputFile, List<String> protoPaths) {
        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        // Run from the workspace root so --path values are interpreted within the workspace context.
        def args = [bufBinary.absolutePath, 'build', '.', '-o', outputFile.absolutePath]
        protoPaths.each { p ->
            args.addAll(['--path', p])
        }

        ExecResult result = getExecOperations().exec { spec ->
            spec.workingDir = workspaceRoot
            spec.commandLine args
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "buf build failed for workspace\n" +
                    "Exit code: ${result.exitValue}\n" +
                    "Check that proto files are valid and all workspace imports are resolvable."
            )
        }
    }

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
     * workspace root) to be passed to {@code buf build --path}.
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
