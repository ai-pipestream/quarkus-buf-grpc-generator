package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
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

    @Inject
    protected abstract ExecOperations getExecOperations()

    @TaskAction
    void build() {
        def exportDir = getExportDir().get().asFile
        def descriptorFile = getDescriptorPath().get().asFile

        // Ensure output directory exists
        descriptorFile.parentFile.mkdirs()

        if (!exportDir.exists() || exportDir.listFiles()?.length == 0) {
            logger.warn("No exported protos found in ${exportDir}, skipping descriptor build.")
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
}
