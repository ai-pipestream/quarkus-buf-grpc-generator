package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

/**
 * Runs buf breaking to detect breaking changes in proto files.
 *
 * Compares the current proto files against a reference (BSR module, git ref, or local directory)
 * to detect breaking changes that could affect API compatibility.
 */
abstract class CheckBreakingTask extends DefaultTask {

    /**
     * Directory containing current proto files to check.
     */
    @InputDirectory
    abstract DirectoryProperty getProtoDir()

    /**
     * The buf executable (resolved from Maven Central).
     */
    @InputFiles
    abstract ConfigurableFileCollection getBufExecutable()

    /**
     * Reference to compare against. Can be:
     *
     * <ul>
     *   <li>BSR reference: <code>buf.build/org/module</code></li>
     *   <li>Git reference: <code>https://github.com/org/repo.git#ref=main</code></li>
     *   <li>Local directory: <code>/path/to/previous/protos</code></li>
     * </ul>
     */
    @Input
    abstract Property<String> getAgainstRef()

    /**
     * Additional arguments to pass to buf breaking.
     *
     * <p>Example: <code>['--config', 'buf.yaml']</code></p>
     */
    @Input
    abstract ListProperty<String> getBreakingArgs()

    @Inject
    protected abstract ExecOperations getExecOperations()

    CheckBreakingTask() {
        description = 'Checks for breaking changes in proto files'
        group = 'protobuf'
        getBreakingArgs().convention([])
    }

    @TaskAction
    void checkBreaking() {
        def protoDir = getProtoDir().get().asFile
        def againstRef = getAgainstRef().getOrNull()

        if (!protoDir.exists() || protoDir.listFiles()?.length == 0) {
            logger.warn("No proto files found in ${protoDir}. Run fetchProtos first.")
            return
        }

        if (!againstRef) {
            throw new GradleException("No reference specified for breaking change detection. " +
                "Set againstRef to a BSR module, git URL, or local directory.")
        }

        def bufBinary = resolveBufBinary()
        logger.lifecycle("Checking for breaking changes in: ${protoDir}")
        logger.lifecycle("Comparing against: ${againstRef}")

        // Build command: buf breaking <dir> --against <ref> [args...]
        def command = [bufBinary.absolutePath, 'breaking', protoDir.absolutePath, '--against', againstRef]
        command.addAll(getBreakingArgs().get())

        def stdout = new ByteArrayOutputStream()
        def stderr = new ByteArrayOutputStream()

        ExecResult result = getExecOperations().exec { spec ->
            spec.commandLine command
            spec.standardOutput = stdout
            spec.errorOutput = stderr
            spec.ignoreExitValue = true
        }

        def output = stdout.toString() + stderr.toString()

        if (result.exitValue != 0) {
            logger.error("Breaking changes detected:\n${output}")
            throw new GradleException("buf breaking found breaking changes (exit code ${result.exitValue}). See output above for details.")
        }

        if (output.trim()) {
            logger.lifecycle(output)
        }
        logger.lifecycle("No breaking changes detected")
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
}
