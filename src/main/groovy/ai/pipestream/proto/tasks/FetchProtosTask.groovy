package ai.pipestream.proto.tasks

import ai.pipestream.proto.ProtoModule
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Fetches proto definitions from BSR or Git using buf export.
 *
 * <p>This task cleans and re-exports all configured modules to ensure
 * a consistent state. Each module is exported to its own subdirectory.</p>
 * <p>For 'git-proto-workspace' mode, performs a Git checkout of the repository into the export directory.
 * Code generation is then performed against the workspace root with <code>--path</code> filtering.</p>
 */
abstract class FetchProtosTask extends DefaultTask {

    /**
     * Source mode: 'bsr', 'git', or 'git-proto-workspace'.
     */
    @Input
    abstract Property<String> getSourceMode()

    /**
         * Extension-level git repository URL (for proto workspace mode).
     */
    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getExtensionGitRepo()

    /**
         * Extension-level git ref (for proto workspace mode).
     */
    @Input
    @org.gradle.api.tasks.Optional
    abstract Property<String> getExtensionGitRef()

    /**
     * Output directory for exported protos.
     */
    @OutputDirectory
    abstract DirectoryProperty getExportDir()

    /**
     * The buf executable (resolved from Maven Central).
     */
    @InputFiles
    abstract ConfigurableFileCollection getBufExecutable()

    /**
     * Module data captured during configuration phase for configuration cache compatibility.
     * Each entry is a map with: name, bsr (optional), gitRepo (optional), gitRef, gitSubdir
     */
    @Input
    abstract ListProperty<Map<String, String>> getModuleData()

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void fetch() {
        def exportDir = getExportDir().get().asFile
        def mode = getSourceMode().get()
        def moduleDataList = getModuleData().get()

        // Clean export directory for consistent state
        if (exportDir.exists()) {
            logger.lifecycle("Cleaning export directory: ${exportDir}")
            exportDir.deleteDir()
        }
        exportDir.mkdirs()

        if (!moduleDataList || moduleDataList.isEmpty()) {
            logger.warn("No modules configured, nothing to fetch.")
            return
        }

        if (mode == 'git-proto-workspace') {
            this.fetchFromGitProtoWorkspace(moduleDataList, exportDir)
        } else {
            moduleDataList.each { moduleData ->
                def moduleName = moduleData['name']
                def moduleDir = new File(exportDir, moduleName)
                moduleDir.mkdirs()

                if (mode == 'bsr') {
                    this.fetchFromBsr(moduleData, moduleDir)
                } else if (mode == 'git') {
                    this.fetchFromGit(moduleData, moduleDir)
                } else {
                    throw new GradleException("Unknown sourceMode: ${mode}. Use 'bsr', 'git', or 'git-proto-workspace'.")
                }
            }
        }

        logger.lifecycle("Fetched ${moduleDataList.size()} module(s) to ${exportDir}")
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

    protected void fetchFromBsr(Map<String, String> moduleData, File outputDir) {
        def moduleName = moduleData['name']
        def bsr = moduleData['bsr']
        if (!bsr) {
            throw new GradleException("Module '${moduleName}' has no BSR path configured. " +
                "Set bsr = 'buf.build/org/module' or use -PprotoSource=git")
        }

        logger.lifecycle("Exporting ${moduleName} from BSR: ${bsr}")

        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine bufBinary.absolutePath, 'export', bsr, '--output', outputDir.absolutePath
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "Failed to export '${moduleName}' from BSR: ${bsr}\n" +
                "Exit code: ${result.exitValue}\n" +
                "Ensure you're authenticated to BSR (run 'buf registry login')."
            )
        }
    }

    protected void fetchFromGit(Map<String, String> moduleData, File outputDir) {
        def moduleName = moduleData['name']
        def gitRepo = moduleData['gitRepo']
        if (!gitRepo) {
            throw new GradleException("Module '${moduleName}' has no Git repository configured. " +
                "Set gitRepo = 'https://github.com/org/repo.git' or use -PprotoSource=bsr")
        }

        def gitRef = moduleData['gitRef'] ?: 'main'
        def gitSubdir = moduleData['gitSubdir'] ?: '.'

        // buf export can handle git URLs directly with ref and subdir
        // Format: <repo>#ref=<ref>,subdir=<subdir>
        def bufGitUrl = "${gitRepo}#ref=${gitRef}"
        if (gitSubdir && gitSubdir != '.') {
            bufGitUrl += ",subdir=${gitSubdir}"
        }

        logger.lifecycle("Exporting ${moduleName} from Git: ${gitRepo} (ref=${gitRef}, subdir=${gitSubdir})")

        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine bufBinary.absolutePath, 'export', bufGitUrl, '--output', outputDir.absolutePath
            spec.ignoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw new GradleException(
                "Failed to export '${moduleName}' from Git: ${gitRepo}\n" +
                "Exit code: ${result.exitValue}\n" +
                "Ensure you have access to the repository."
            )
        }
    }

    /**
     * Fetches a proto workspace from Git by checking out the repository to {@code exportDir}.
     *
     * <p>This keeps the original workspace structure (including {@code buf.yaml} and module paths)
     * so downstream tasks can run {@code buf generate} and {@code buf build} against the workspace root
     * using {@code --path} filters for only the registered modules.</p>
     */
    protected void fetchFromGitProtoWorkspace(List<Map<String, String>> moduleDataList, File exportDir) {
        def extensionGitRepo = getExtensionGitRepo().getOrNull()
        def extensionGitRef = getExtensionGitRef().getOrElse('main')

        if (!extensionGitRepo) {
            throw new GradleException("git-proto-workspace mode requires gitRepo to be set at extension level. " +
                "Set gitRepo = 'https://github.com/org/repo.git' in pipestreamProtos { }")
        }

        logger.lifecycle("Checking out proto workspace from Git: ${extensionGitRepo} (ref=${extensionGitRef})")
        logger.lifecycle("Registered module(s): ${moduleDataList.collect { it['name'] }.join(', ')}")

        // Prefer a shallow clone when ref is a branch or tag; fall back to a full clone + checkout for safety.
        ExecResult shallowCloneResult = getExecOperations().exec { spec ->
            spec.commandLine 'git', 'clone', '--depth', '1', '--single-branch', '--branch', extensionGitRef,
                extensionGitRepo, exportDir.absolutePath
            spec.ignoreExitValue = true
        }

        if (shallowCloneResult.exitValue == 0) {
            return
        }

        logger.lifecycle("Shallow clone failed for ref '${extensionGitRef}'. Falling back to clone + checkout.")

        ExecResult cloneResult = getExecOperations().exec { spec ->
            spec.commandLine 'git', 'clone', extensionGitRepo, exportDir.absolutePath
            spec.ignoreExitValue = true
        }

        if (cloneResult.exitValue != 0) {
            throw new GradleException(
                "Failed to clone proto workspace from Git: ${extensionGitRepo}\n" +
                    "Exit code: ${cloneResult.exitValue}\n" +
                    "Ensure you have access to the repository."
            )
        }

        ExecResult checkoutResult = getExecOperations().exec { spec ->
            spec.workingDir = exportDir
            spec.commandLine 'git', 'checkout', extensionGitRef
            spec.ignoreExitValue = true
        }

        if (checkoutResult.exitValue != 0) {
            throw new GradleException(
                "Failed to checkout ref '${extensionGitRef}' in repo: ${extensionGitRepo}\n" +
                    "Exit code: ${checkoutResult.exitValue}"
            )
        }
    }
}
