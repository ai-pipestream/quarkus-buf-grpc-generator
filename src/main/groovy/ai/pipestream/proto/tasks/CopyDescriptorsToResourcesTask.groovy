package ai.pipestream.proto.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies protobuf descriptor files to test resource directories.
 *
 * <p>This task copies the generated descriptor file to the test build resources directory,
 * making it available on the test classpath. Generated files are only copied to build
 * directories, never to source directories, following Gradle best practices.</p>
 *
 * <p>By default, copies to: <code>build/resources/test/grpc/</code></p>
 *
 * <p>This eliminates the need for manual copy tasks in build.gradle files.</p>
 */
abstract class CopyDescriptorsToResourcesTask extends DefaultTask {

    /**
     * The generated descriptor file to copy.
     */
    @InputFile
    abstract RegularFileProperty getDescriptorFile()

    /**
     * Target directory for test build resources (classpath approach).
     *
     * <p>Default: <code>build/resources/test/grpc</code></p>
     * <p>This is the only output directory - we never copy to source directories.</p>
     */
    @OutputDirectories
    abstract DirectoryProperty getTestBuildDir()

    @TaskAction
    void copy() {
        def descriptorFile = getDescriptorFile().get().asFile
        if (!descriptorFile.exists()) {
            logger.warn("Descriptor file does not exist: ${descriptorFile}, skipping copy")
            return
        }

        def testBuildDir = getTestBuildDir().get().asFile

        // Ensure directory exists
        testBuildDir.mkdirs()

        // Copy to test build dir (only build directory, never source)
        def testBuildTarget = new File(testBuildDir, descriptorFile.name)
        Files.copy(
            descriptorFile.toPath(),
            testBuildTarget.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("Copied descriptor to test build dir: ${testBuildTarget}")
    }
}
