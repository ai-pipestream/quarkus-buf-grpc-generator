package ai.pipestream.proto

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class GitAuthFunctionalTest extends Specification {
    @TempDir File testProjectDir
    File buildFile
    File settingsFile

    def setup() {
        buildFile = new File(testProjectDir, 'build.gradle')
        settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "rootProject.name = 'test-project'"
    }

    def "masks authenticated URL in logs when token is provided"() {
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
                    register("test-auth") {
                        // Use a dummy URL that will cause a failure, but we just want to check logs
                        gitRepo = "https://example.com/repo.git"
                        gitAuthToken = "my-secret-token"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos')
            .forwardOutput()
            .buildAndFail() // Expect failure because repo doesn't exist

        println "BUILD OUTPUT:\n" + result.output

        then:
        // Verify we attempted to inject auth
        assert result.output.contains("Using authenticated Git URL for module 'test-auth'") : "Did not find expected log message. Full output:\n" + result.output
        
        // Verify the URL was masked in the lifecycle log
        result.output.contains("Exporting test-auth from Git: (authenticated URL hidden)")
        
        // Verify the secret token is NOT leaked in the output
        !result.output.contains("my-secret-token")
    }

    def "injects username and token when both provided"() {
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
                    register("test-user-auth") {
                        gitRepo = "https://example.com/repo.git"
                        gitAuthUser = "myuser"
                        gitAuthToken = "mytoken"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos')
            .forwardOutput()
            .buildAndFail()

        then:
        result.output.contains("Using authenticated Git URL for module 'test-user-auth'")
        result.output.contains("Exporting test-user-auth from Git: (authenticated URL hidden)")
        
        // Ensure credentials didn't leak
        !result.output.contains("myuser:mytoken")
    }

    def "ignores credentials for non-http URLs"() {
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
                    register("ssh-module") {
                        gitRepo = "git@github.com:org/repo.git"
                        gitAuthToken = "ignored-token"
                    }
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments('fetchProtos')
            .forwardOutput()
            .buildAndFail()

        then:
        result.output.contains("Authentication credentials provided but Git URL appears to be SSH/SCP-style")
        // Even though auth was ignored, we mask the log because a token was provided
        result.output.contains("Exporting ssh-module from Git: (authenticated URL hidden)")
    }
}
