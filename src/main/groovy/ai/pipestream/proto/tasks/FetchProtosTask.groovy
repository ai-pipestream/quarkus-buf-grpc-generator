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
 */
abstract class FetchProtosTask extends DefaultTask {

    /**
     * Source mode: 'bsr' or 'git'.
     */
    @Input
    abstract Property<String> getSourceMode()

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

        moduleDataList.each { moduleData ->
            def moduleName = moduleData['name']
            def moduleDir = new File(exportDir, moduleName)
            moduleDir.mkdirs()

            if (mode == 'bsr') {
                this.fetchFromBsr(moduleData, moduleDir)
            } else if (mode == 'git') {
                this.fetchFromGit(moduleData, moduleDir)
            } else {
                throw new GradleException("Unknown sourceMode: ${mode}. Use 'bsr' or 'git'.")
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
}
