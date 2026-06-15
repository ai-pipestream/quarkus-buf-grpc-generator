package ai.pipestream.proto

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class PluginFunctionalTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "rootProject.name = 'test-project'"
    }

    def "plugin can be applied without errors"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('tasks', '--group=protobuf')
            .build()

        then:
        // Core tasks
        result.output.contains('fetchProtos')
        result.output.contains('prepareGenerators')
        result.output.contains('generateProtos')
        result.output.contains('buildDescriptors')
        result.output.contains('cleanProtos')
        // Quality tasks
        result.output.contains('lintProtos')
        result.output.contains('checkBreaking')
        result.output.contains('formatProtos')
        result.output.contains('checkFormatProtos')
    }

    def "can configure modules via DSL"() {
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
                    register("test-module") {
                        bsr = "buf.build/bufbuild/buf"
                    }
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
        new File(testProjectDir, 'build/protos/export/test-module').exists()
    }

    def "can configure multiple modules"() {
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
                    register("module1") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                    register("module2") {
                        bsr = "buf.build/bufbuild/protovalidate"
                    }
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
        new File(testProjectDir, 'build/protos/export/module1').exists()
        new File(testProjectDir, 'build/protos/export/module2').exists()
    }

    def "prepareGenerators creates buf.gen.yaml with absolute paths"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        bufGenYaml.exists()

        def content = bufGenYaml.text
        // v2 local format checks
        content.contains('version: v2')
        content.contains('protoc_builtin: java')
        content.contains('protoc_path:')
        content.contains('local:')  // grpc-java and mutiny use local:
        content.contains('protoc-gen-mutiny')
        // Verify absolute paths
        content.contains(testProjectDir.absolutePath)
    }

    def "prepareGenerators creates mutiny wrapper scripts for both platforms"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        // Unix shell script
        def mutinyScript = new File(testProjectDir, 'build/tmp/protoc-plugins/protoc-gen-mutiny')
        mutinyScript.exists()
        mutinyScript.canExecute()
        mutinyScript.text.contains('MutinyGrpcGenerator')
        mutinyScript.text.contains('#!/bin/sh')

        // Windows batch file
        def mutinyBat = new File(testProjectDir, 'build/tmp/protoc-plugins/protoc-gen-mutiny.bat')
        mutinyBat.exists()
        mutinyBat.text.contains('MutinyGrpcGenerator')
        mutinyBat.text.contains('@echo off')
    }

    def "cleanProtos removes all generated artifacts"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        // First generate some artifacts
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators')
            .build()

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('cleanProtos')
            .build()

        then:
        result.task(":cleanProtos").outcome == TaskOutcome.SUCCESS
        !new File(testProjectDir, 'build/protos/export').exists()
        !new File(testProjectDir, 'build/buf.gen.yaml').exists()
        !new File(testProjectDir, 'build/tmp/protoc-plugins').exists()
    }

    def "can disable gRPC generation"() {
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
                generateGrpc = false
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        // v2 local format - Java is still included
        content.contains('protoc_builtin: java')
        // gRPC local plugin should NOT be in the output (only mutiny local: for the mutiny wrapper)
        // When gRPC is disabled, the only local: entries are for mutiny
        !content.contains('protoc-gen-grpc-java')
    }

    def "can disable Mutiny generation"() {
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
                generateMutiny = false
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        !content.contains('protoc-gen-mutiny')
        !content.contains('mutiny')
    }

    def "can configure custom Quarkus version"() {
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
                quarkusGrpcVersion = '3.17.0'
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.output.contains('quarkus-grpc-protoc-plugin:3.17.0')
    }

    def "can configure extra plugins"() {
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
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
                extraPlugins {
                    register("doc") {
                        plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
                        out = "docs"
                        opt = ["markdown,docs.md"]
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        def content = bufGenYaml.text
        content.contains('buf.build/community/pseudomuto-protoc-gen-doc')
        content.contains('markdown,docs.md')
    }

    def "skips fetchProtos when no modules configured"() {
        given:
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
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
        result.output.contains('No modules configured')
    }

    def "can configure custom protoc path"() {
        given:
        // Create a fake protoc script for testing
        def fakeProtoc = new File(testProjectDir, 'fake-protoc')
        fakeProtoc.text = "#!/bin/sh\necho 'fake protoc'"
        fakeProtoc.setExecutable(true)

        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                protocPath = '${fakeProtoc.absolutePath}'
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.output.contains("Using custom protoc path: ${fakeProtoc.absolutePath}")

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        bufGenYaml.text.contains(fakeProtoc.absolutePath)
    }

    def "can configure custom grpc-java plugin path"() {
        given:
        // Create fake plugin scripts for testing
        def fakeGrpcJava = new File(testProjectDir, 'fake-grpc-java')
        fakeGrpcJava.text = "#!/bin/sh\necho 'fake grpc-java'"
        fakeGrpcJava.setExecutable(true)

        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                grpcJavaPluginPath = '${fakeGrpcJava.absolutePath}'
                modules {
                    register("test") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('prepareGenerators', '--stacktrace')
            .forwardOutput()
            .build()

        then:
        result.task(":prepareGenerators").outcome == TaskOutcome.SUCCESS
        result.output.contains("Using custom grpc-java plugin path: ${fakeGrpcJava.absolutePath}")

        def bufGenYaml = new File(testProjectDir, 'build/buf.gen.yaml')
        bufGenYaml.text.contains(fakeGrpcJava.absolutePath)
    }

    def "descriptorOnly keeps the descriptor but prunes generated Java even with generateMutiny/generateGrpc = true"() {
        given: "a descriptorOnly project that ALSO has codegen flags on"
        buildFile << """
            plugins {
                id 'ai.pipestream.proto-toolchain'
                id 'java'
            }

            repositories {
                mavenCentral()
            }

            pipestreamProtos {
                descriptorOnly = true
                // Codegen flags are deliberately ON: descriptorOnly must STILL
                // leave no generated Java for compilation (the robustness contract
                // — disabling tasks by name was not enough).
                generateMutiny = true
                generateGrpc = true
                modules {
                    register("test-module") {
                        bsr = "buf.build/bufbuild/buf"
                    }
                }
            }
        """

        when: "generation runs, then the descriptor is built, then the prune fires"
        // pruneGeneratedSources dependsOn buildDescriptors and mustRunAfter
        // generateProtos, so requesting both gives: generate -> descriptor -> prune.
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('generateProtos', 'pruneGeneratedSources', '--stacktrace')
            .forwardOutput()
            .build()

        then: "the full pipeline ran"
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS
        result.task(":buildDescriptors").outcome == TaskOutcome.SUCCESS
        result.task(":pruneGeneratedSources").outcome == TaskOutcome.SUCCESS

        and: "the descriptor set survives"
        new File(testProjectDir, 'build/descriptors/proto.desc').exists()

        and: "NO generated Java survives — it was deleted after the descriptor was built"
        def generatedRoot = new File(testProjectDir, 'build/generated')
        def leftoverJava = []
        if (generatedRoot.exists()) {
            generatedRoot.eachFileRecurse { if (it.name.endsWith('.java')) leftoverJava << it }
        }
        leftoverJava.isEmpty()
    }

    def "without descriptorOnly, generated Java is kept and wired as a source root"() {
        given: "the same project WITHOUT descriptorOnly (the default)"
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
                    register("test-module") {
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

        then: "generated Java IS present (the default behaviour the prune contract is measured against)"
        result.task(":generateProtos").outcome == TaskOutcome.SUCCESS
        def generatedRoot = new File(testProjectDir, 'build/generated')
        def javaFiles = []
        if (generatedRoot.exists()) {
            generatedRoot.eachFileRecurse { if (it.name.endsWith('.java')) javaFiles << it }
        }
        !javaFiles.isEmpty()
    }
}
