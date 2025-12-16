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
 * <p>This task copies the generated descriptor file to configurable target directories,
 * typically for use in testing frameworks that need descriptors on the classpath
 * or in specific resource locations.</p>
 *
 * <p>By default, copies to:</p>
 * <ol>
 *   <li><code>src/test/resources/grpc/</code> (for test resources)</li>
 *   <li><code>build/resources/test/grpc/</code> (for test build resources)</li>
 * </ol>
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
     * Target directory for test resources (source directory approach).
     *
     * <p>Default: <code>src/test/resources/grpc</code></p>
     */
    @OutputDirectories
    abstract DirectoryProperty getTestResourcesDir()

    /**
     * Target directory for test build resources (classpath approach).
     *
     * <p>Default: <code>build/resources/test/grpc</code></p>
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

        def testResourcesDir = getTestResourcesDir().get().asFile
        def testBuildDir = getTestBuildDir().get().asFile

        // Ensure directories exist
        testResourcesDir.mkdirs()
        testBuildDir.mkdirs()

        // Copy to test resources
        def testResourcesTarget = new File(testResourcesDir, descriptorFile.name)
        Files.copy(
            descriptorFile.toPath(),
            testResourcesTarget.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("Copied descriptor to test resources: ${testResourcesTarget}")

        // Copy to test build dir
        def testBuildTarget = new File(testBuildDir, descriptorFile.name)
        Files.copy(
            descriptorFile.toPath(),
            testBuildTarget.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("Copied descriptor to test build dir: ${testBuildTarget}")
    }
}
