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
 * <p>For 'git-proto-workspace' mode, exports the entire repository once (detecting the workspace), then filters to registered modules.</p>
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
    abstract Property<String> getExtensionGitRepo()

    /**
         * Extension-level git ref (for proto workspace mode).
     */
    @Input
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
     * Fetches from a Git monorepo by exporting the entire repository once,
     * then filtering to only the registered modules.
     * This approach supports cross-module imports.
     */
        protected void fetchFromGitProtoWorkspace(List<Map<String, String>> moduleDataList, File exportDir) {
        def extensionGitRepo = getExtensionGitRepo().getOrNull()
        def extensionGitRef = getExtensionGitRef().getOrElse('main')

        if (!extensionGitRepo) {
            throw new GradleException("git-proto-workspace mode requires gitRepo to be set at extension level. " +
                "Set gitRepo = 'https://github.com/org/repo.git' in pipestreamProtos { }")
        }

        logger.lifecycle("Exporting entire monorepo from Git: ${extensionGitRepo} (ref=${extensionGitRef})")
        logger.lifecycle("Will filter to ${moduleDataList.size()} module(s): ${moduleDataList.collect { it['name'] }.join(', ')}")

        def bufBinary = resolveBufBinary()
        logger.info("Using buf binary: ${bufBinary.absolutePath}")

        // Strategy for proto workspace mode:
        // Export entire repo once (no subdir) - buf will detect the workspace (buf.yaml with modules:)
        // and export all proto files together, allowing cross-module imports to resolve.
        // The exported structure is flattened to package paths (ai/pipestream/...).
        // We then filter to registered modules by matching package paths.
        
        // Export entire repo to staging (no subdir = workspace detection)
        def stagingDir = new File(exportDir.parentFile, "protos-staging-workspace")
        if (stagingDir.exists()) {
            stagingDir.deleteDir()
        }
        stagingDir.mkdirs()
        
        def wholeRepoUrl = "${extensionGitRepo}#ref=${extensionGitRef}"
        logger.lifecycle("Exporting entire repository (workspace mode) to staging...")
        logger.lifecycle("Buf will detect the workspace (buf.yaml with modules:) and export all modules together, allowing cross-module imports.")
        
        ExecResult wholeRepoResult = getExecOperations().exec { spec ->
            spec.commandLine bufBinary.absolutePath, 'export', wholeRepoUrl, '--output', stagingDir.absolutePath
            spec.ignoreExitValue = true
        }
        
        if (wholeRepoResult.exitValue != 0) {
            throw new GradleException(
                "Failed to export entire monorepo from Git: ${extensionGitRepo}\n" +
                "Exit code: ${wholeRepoResult.exitValue}\n" +
                "Ensure you have access to the repository and that the root buf.yaml defines a workspace."
            )
        }
        
        // Map module names to their expected package path prefixes
        // The package prefix is determined by the actual proto package declarations, not module names
        // We need to look at the module's subdirectory structure to infer the package prefix
        def modulePackageMap = [:]
        moduleDataList.each { moduleData ->
            def moduleName = moduleData['name']
            def moduleSubdir = moduleData['gitSubdir'] ?: moduleName
            
            // Default: assume package matches module name
            def packagePrefix = "ai/pipestream/${moduleName}"
            
            // Handle known module-to-package mappings
            // These are based on the actual proto package declarations in each module
            if (moduleName == 'common' || moduleSubdir == 'common') {
                // Common module uses ai.pipestream.data package
                packagePrefix = "ai/pipestream/data"
            } else if (moduleName == 'config' || moduleSubdir == 'config') {
                packagePrefix = "ai/pipestream/config"
            } else if (moduleName == 'opensearch' || moduleSubdir == 'opensearch') {
                packagePrefix = "ai/pipestream/opensearch"
            } else if (moduleName == 'schemamanager' || moduleSubdir == 'schemamanager') {
                packagePrefix = "ai/pipestream/schemamanager"
            } else {
                // For other modules, try the module name first
                packagePrefix = "ai/pipestream/${moduleName}"
            }
            
            modulePackageMap[moduleName] = packagePrefix
            logger.debug("Module '${moduleName}' (subdir: ${moduleSubdir}) mapped to package prefix: ${packagePrefix}")
        }
        
        // Filter exported files to registered modules based on package paths
        moduleDataList.each { moduleData ->
            def moduleName = moduleData['name']
            def moduleDir = new File(exportDir, moduleName)
            moduleDir.mkdirs()
            
            def packagePrefix = modulePackageMap[moduleName]
            def foundFiles = false
            
            // Copy all proto files that match this module's package prefix
            stagingDir.eachFileRecurse { file ->
                if (file.isFile() && file.name.endsWith('.proto')) {
                    def relativePath = stagingDir.toPath().relativize(file.toPath()).toString()
                    // Check if this file belongs to the module based on package path
                    if (relativePath.startsWith(packagePrefix + '/') || relativePath == packagePrefix + '.proto') {
                        def targetFile = new File(moduleDir, relativePath)
                        targetFile.parentFile.mkdirs()
                        file.withInputStream { input ->
                            targetFile.withOutputStream { output ->
                                output << input
                            }
                        }
                        foundFiles = true
                    }
                }
            }
            
            // Also copy any imported proto files from other registered modules
            // (needed for cross-module imports)
            moduleDataList.each { otherModule ->
                if (otherModule['name'] != moduleName) {
                    def otherPackagePrefix = modulePackageMap[otherModule['name']]
                    stagingDir.eachFileRecurse { file ->
                        if (file.isFile() && file.name.endsWith('.proto')) {
                            def relativePath = stagingDir.toPath().relativize(file.toPath()).toString()
                            if (relativePath.startsWith(otherPackagePrefix + '/') || relativePath == otherPackagePrefix + '.proto') {
                                // Check if this file is imported by checking proto file contents
                                // For now, we'll copy all proto files from registered modules
                                // This ensures cross-module imports work
                                def targetFile = new File(moduleDir, relativePath)
                                if (!targetFile.exists()) {
                                    targetFile.parentFile.mkdirs()
                                    file.withInputStream { input ->
                                        targetFile.withOutputStream { output ->
                                            output << input
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (!foundFiles) {
                logger.warn("Warning: Could not find proto files for module '${moduleName}' with package prefix '${packagePrefix}'. " +
                    "Module may be empty or package structure differs.")
            } else {
                logger.info("Copied proto files for module '${moduleName}' (package prefix: ${packagePrefix})")
            }
        }
        
        // Clean up staging directory
        stagingDir.deleteDir()

        logger.lifecycle("Successfully filtered ${moduleDataList.size()} module(s) from monorepo workspace")
    }

    /**
     * Recursively copies a directory.
     */
    protected void copyDirectory(File sourceDir, File targetDir) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new GradleException("Source directory does not exist or is not a directory: ${sourceDir}")
        }

        targetDir.mkdirs()

        sourceDir.eachFile { sourceFile ->
            def targetFile = new File(targetDir, sourceFile.name)
            if (sourceFile.isDirectory()) {
                copyDirectory(sourceFile, targetFile)
            } else {
                sourceFile.withInputStream { input ->
                    targetFile.withOutputStream { output ->
                        output << input
                    }
                }
            }
        }
    }
}
