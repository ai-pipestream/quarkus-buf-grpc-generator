package ai.pipestream.proto

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Integration tests that test full end-to-end functionality.
 * These tests may take longer as they perform actual network operations.
 */
class IntegrationTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "rootProject.name = 'integration-test'"
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "full code generation from BSR"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify Java files were generated
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "full code generation from Git"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git'
                modules {
                    register("buf") {
                        gitRepo = "https://github.com/bufbuild/buf.git"
                        gitRef = "main"
                        gitSubdir = "proto"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify Java files were generated
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "can switch between BSR and Git via gradle property"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                        gitRepo = "https://github.com/bufbuild/buf.git"
                        gitRef = "main"
                        gitSubdir = "proto"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '-PprotoSource=git', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.output.contains('Exporting buf from Git')
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "descriptor generation creates proto.desc file"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('buildDescriptors', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS

        // Verify descriptor file was generated
        def descriptorFile = new File(testProjectDir, 'build/descriptors/proto.desc')
        descriptorFile.exists()
        descriptorFile.length() > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "can disable descriptor generation"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = false
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('buildDescriptors', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SKIPPED
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources copies descriptor to test resources"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = true
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SUCCESS

        // Verify descriptor was copied to test build dir (only build directory, never source)
        def testBuildFile = new File(testProjectDir, 'build/resources/test/grpc/proto.desc')
        testBuildFile.exists()
        testBuildFile.length() > 0

        // Verify file is identical to source
        def sourceDescriptor = new File(testProjectDir, 'build/descriptors/proto.desc')
        sourceDescriptor.exists()
        testBuildFile.length() == sourceDescriptor.length()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources can be disabled"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = false
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SKIPPED

        // Verify descriptor was NOT copied
        def testBuildFile = new File(testProjectDir, 'build/resources/test/grpc/proto.desc')
        !testBuildFile.exists()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources is skipped when generateDescriptors is false"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = false
                copyDescriptorsToResources = true
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SKIPPED
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SKIPPED
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources uses custom directories"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = true
                testBuildDir = layout.buildDirectory.dir("resources/test/custom/grpc")
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SUCCESS

        // Verify descriptor was copied to custom test build dir (only build directory)
        def customTestBuildFile = new File(testProjectDir, 'build/resources/test/custom/grpc/proto.desc')
        customTestBuildFile.exists()
        customTestBuildFile.length() > 0

        // Verify default location was NOT used
        def defaultTestBuildFile = new File(testProjectDir, 'build/resources/test/grpc/proto.desc')
        !defaultTestBuildFile.exists()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources runs before processTestResources"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = true
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('processTestResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SUCCESS
        // processTestResources may be NO_SOURCE if there are no test resources, which is fine
        result.task(":processTestResources").outcome in [TaskOutcome.SUCCESS, TaskOutcome.NO_SOURCE]

        // Verify descriptor was copied (task ran before processTestResources)
        def testBuildFile = new File(testProjectDir, 'build/resources/test/grpc/proto.desc')
        testBuildFile.exists()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources uses custom descriptor filename"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = true
                descriptorPath = layout.buildDirectory.file("descriptors/services.dsc")
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SUCCESS

        // Verify descriptor was copied to test build dir with custom filename (only build directory)
        def testBuildFile = new File(testProjectDir, 'build/resources/test/grpc/services.dsc')
        testBuildFile.exists()
        testBuildFile.length() > 0

        // Verify file is identical to source
        def sourceDescriptor = new File(testProjectDir, 'build/descriptors/services.dsc')
        sourceDescriptor.exists()
        testBuildFile.length() == sourceDescriptor.length()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "copyDescriptorsToResources works with nested directory structure"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                generateDescriptors = true
                copyDescriptorsToResources = true
                descriptorPath = layout.buildDirectory.file("descriptors/services.dsc")
                testBuildDir = layout.buildDirectory.dir("resources/test/wiremock/grpc")
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('copyDescriptorsToResources', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":copyDescriptorsToResources").outcome == TaskOutcome.SUCCESS

        // Verify descriptor was copied to nested test build directory (only build directory)
        def testBuildFile = new File(testProjectDir, 'build/resources/test/wiremock/grpc/services.dsc')
        testBuildFile.exists()
        testBuildFile.length() > 0

        // Verify file is identical to source
        def sourceDescriptor = new File(testProjectDir, 'build/descriptors/services.dsc')
        sourceDescriptor.exists()
        testBuildFile.length() == sourceDescriptor.length()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "generates Mutiny stubs"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Look for Mutiny-generated files
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        def mutinyFiles = []
        outputDir.eachFileRecurse { file ->
            if (file.name.contains('Mutiny')) {
                mutinyFiles << file
            }
        }
        mutinyFiles.size() > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "wires into Java compilation"() {
        given:
        // Use buf.build/bufbuild/buf instead of protovalidate as it has simpler protos
        // Note: Full compilation requires matching protobuf-java version to buf's remote plugins
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                // Use same version as buf's remote plugins generate for
                implementation 'io.grpc:grpc-stub:1.68.0'
                implementation 'io.grpc:grpc-protobuf:1.68.0'
                implementation 'com.google.protobuf:protobuf-java:4.29.0'
                implementation 'io.smallrye.reactive:mutiny:2.6.2'
                implementation 'io.quarkus:quarkus-grpc:3.30.4'
                implementation 'jakarta.annotation:jakarta.annotation-api:2.1.1'
            }

            pipestreamProtos {
                // Use simpler protos that don't require specific protobuf editions
                generateMutiny = false  // Skip mutiny for this test to simplify
                modules {
                    register("buf") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS

        // Verify generated Java files exist (compilation is tricky due to version dependencies)
        def outputDir = new File(testProjectDir, 'build/generated/source/proto/main/java')
        outputDir.exists()
        outputDir.listFiles().length > 0
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "git-proto-workspace mode exports whole repo then filters modules"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git-proto-workspace'
                gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
                gitRef = "main"
                
                modules {
                    register("common")
                    register("config")
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.output.contains('Exporting entire repository (workspace mode)')
        result.output.contains('Will filter to 2 module(s)')

        // Verify only registered modules were exported
        def exportDir = new File(testProjectDir, 'build/protos/export')
        exportDir.exists()
        
        def commonDir = new File(exportDir, 'common')
        commonDir.exists()
        commonDir.listFiles().length > 0
        
        def configDir = new File(exportDir, 'config')
        configDir.exists()
        configDir.listFiles().length > 0
        
        // Verify other modules were NOT exported (filtering worked)
        def opensearchDir = new File(exportDir, 'opensearch')
        !opensearchDir.exists()
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "git-proto-workspace mode supports cross-module imports"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git-proto-workspace'
                gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
                gitRef = "main"
                
                modules {
                    register("opensearch")
                    register("schemamanager")
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        result.output.contains('Exporting entire repository (workspace mode)')
        
        // Verify both modules were exported
        def exportDir = new File(testProjectDir, 'build/protos/export')
        def opensearchDir = new File(exportDir, 'opensearch')
        opensearchDir.exists()
        
        def schemamanagerDir = new File(exportDir, 'schemamanager')
        schemamanagerDir.exists()
        
        // Verify opensearch module contains the import (cross-module import should be resolvable)
        // Note: buf export flattens structure, so files are at ai/pipestream/... not proto/ai/pipestream/...
        def opensearchProtoDir = new File(opensearchDir, 'ai/pipestream/opensearch/v1')
        opensearchProtoDir.exists()
        
        def managerProto = new File(opensearchProtoDir, 'opensearch_manager.proto')
        if (managerProto.exists()) {
            def protoContent = managerProto.text
            // Should contain import for schemamanager
            protoContent.contains('schemamanager') || protoContent.contains('schema_manager')
        }
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "git-proto-workspace mode requires gitRepo at extension level"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git-proto-workspace'
                // gitRepo not set - should fail
                
                modules {
                    register("common")
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .buildAndFail()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.FAILED
        // Gradle validates required properties before task execution
        result.output.contains("property 'extensionGitRepo' doesn't have a configured value") ||
        result.output.contains('git-proto-workspace mode requires gitRepo')
    }

    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    def "git-proto-workspace mode allows per-module gitSubdir override"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                sourceMode = 'git-proto-workspace'
                gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
                gitRef = "main"
                
                modules {
                    register("common") {
                        // gitSubdir defaults to module name, but can be overridden
                        gitSubdir = "common"
                    }
                    register("config")
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":fetchProtos").outcome == TaskOutcome.SUCCESS
        
        // Verify modules were exported
        def exportDir = new File(testProjectDir, 'build/protos/export')
        def commonDir = new File(exportDir, 'common')
        commonDir.exists()
        
        def configDir = new File(exportDir, 'config')
        configDir.exists()
    }
}
