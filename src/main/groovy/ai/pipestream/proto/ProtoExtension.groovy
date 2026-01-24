package ai.pipestream.proto

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Extension for configuring the Pipestream Proto Toolchain plugin.
 *
 * Example usage:
 * <pre>
 * pipestreamProtos {
 *     sourceMode = 'bsr'  // or 'git' or 'git-proto-workspace'
 *     quarkusGrpcVersion = '3.30.6'
 *     generateDescriptors = true
 *
 *     // BSR mode (default)
 *     modules {
 *         register("intake") {
 *             bsr = "buf.build/pipestreamai/intake"
 *         }
 *     }
 *
 *     // Git mode (per-module)
 *     // sourceMode = 'git'
 *     // modules {
 *     //     register("intake") {
 *     //         gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
 *     //         gitRef = "main"
 *     //         gitSubdir = "intake"
 *     //     }
 *     // }
 *
 *     // Git proto workspace mode (recommended for monorepos with cross-module imports)
 *     // sourceMode = 'git-proto-workspace'
 *     // gitRepo = "https://github.com/ai-pipestream/pipestream-protos.git"
 *     // gitRef = "main"
 *     // modules {
 *     //     register("opensearch")      // Just name, toolchain filters from whole repo
 *     //     register("schemamanager")   // Supports cross-module imports
 *     //     register("config")
 *     // }
 *
 *     // Optional: extra arguments for buf generate
 *     bufGenerateArgs = ['--exclude-path', 'google/']
 *
 *     // Optional: additional buf plugins
 *     extraPlugins {
 *         register("doc") {
 *             plugin = "buf.build/community/pseudomuto-protoc-gen-doc"
 *             out = "docs"
 *             opt = ["markdown,docs.md"]
 *         }
 *     }
 * }
 * </pre>
 */
abstract class ProtoExtension {

    private final Project project
    private final NamedDomainObjectContainer<ProtoModule> modules
    private final NamedDomainObjectContainer<BufPlugin> extraPlugins

    ProtoExtension(Project project) {
        this.project = project
        this.modules = project.objects.domainObjectContainer(ProtoModule)
        this.extraPlugins = project.objects.domainObjectContainer(BufPlugin)

        // Set conventions (defaults)
        getSourceMode().convention(
            project.providers.gradleProperty('protoSource').orElse('bsr')
        )
        getBufVersion().convention(BinaryResolver.DEFAULT_BUF_VERSION)
        getProtocVersion().convention(BinaryResolver.DEFAULT_PROTOC_VERSION)
        getGrpcJavaVersion().convention(BinaryResolver.DEFAULT_GRPC_JAVA_VERSION)
        getQuarkusGrpcVersion().convention('3.30.7')
        getGenerateMutiny().convention(true)
        getGenerateGrpc().convention(true)
        getGenerateDescriptors().convention(true)
        getDescriptorPath().convention(
            project.layout.buildDirectory.file("descriptors/proto.desc")
        )
        getOutputDir().convention(
            project.layout.buildDirectory.dir("generated/source/proto/main/java")
        )
        getExportDir().convention(
            project.layout.buildDirectory.dir("protos/export")
        )
        getBufGenerateArgs().convention([])
        getLintArgs().convention([])
        getBreakingArgs().convention([])
        getFormatArgs().convention([])
        getCopyDescriptorsToResources().convention(false)
        getTestBuildDir().convention(
            project.layout.buildDirectory.dir("resources/test/grpc")
        )
        getGitRef().convention("main")
    }

    /**
     * Source mode: <code>'bsr'</code> (default), <code>'git'</code>, or <code>'git-proto-workspace'</code>.
     *
     * <p>Can be overridden via <code>-PprotoSource=git</code></p>
     * <p><code>'git-proto-workspace'</code> exports the entire repository once (detecting the workspace),
     * then filters to registered modules. This mode supports cross-module imports and is recommended for monorepos.</p>
     */
    abstract Property<String> getSourceMode()

    /**
     * Git repository URL for proto workspace mode.
     *
     * <p>Used when <code>sourceMode = 'git-proto-workspace'</code>. Can be overridden per-module if needed.</p>
     * <p>Example: <code>"https://github.com/ai-pipestream/pipestream-protos.git"</code></p>
     */
    @Input
    abstract Property<String> getGitRepo()

    /**
     * Git ref (branch, tag, or commit) for proto workspace mode.
     *
     * <p>Used when <code>sourceMode = 'git-proto-workspace'</code>. Defaults to <code>"main"</code>.</p>
     * <p>Can be overridden per-module if needed.</p>
     */
    @Input
    abstract Property<String> getGitRef()

    /**
     * Buf CLI version to use.
     *
     * <p>Default: <code>1.61.0</code></p>
     * <p>The plugin automatically downloads the appropriate binary for your platform.</p>
     */
    abstract Property<String> getBufVersion()

    /**
     * Protoc version to use.
     *
     * <p>Default: <code>4.33.2</code></p>
     * <p>The plugin automatically downloads the appropriate binary for your platform.</p>
     */
    abstract Property<String> getProtocVersion()

    /**
     * gRPC Java plugin version to use.
     *
     * <p>Default: <code>1.77.0</code></p>
     * <p>The plugin automatically downloads the appropriate binary for your platform.</p>
     */
    abstract Property<String> getGrpcJavaVersion()

    /**
     * Optional: Custom path to protoc binary.
     *
     * <p>If set, the plugin uses this instead of downloading from Maven.</p>
     */
    abstract Property<String> getProtocPath()

    /**
     * Optional: Custom path to protoc-gen-grpc-java binary.
     *
     * <p>If set, the plugin uses this instead of downloading from Maven.</p>
     */
    abstract Property<String> getGrpcJavaPluginPath()

    /**
     * Quarkus gRPC version for the Mutiny generator plugin.
     */
    abstract Property<String> getQuarkusGrpcVersion()

    /**
     * Whether to generate Quarkus Mutiny stubs.
     *
     * <p>Default: <code>true</code></p>
     */
    abstract Property<Boolean> getGenerateMutiny()

    /**
     * Whether to generate standard gRPC stubs.
     *
     * <p>Default: <code>true</code></p>
     */
    abstract Property<Boolean> getGenerateGrpc()

    /**
     * Whether to generate protobuf descriptor files.
     *
     * <p>Default: <code>true</code></p>
     */
    abstract Property<Boolean> getGenerateDescriptors()

    /**
     * Path for the generated descriptor file.
     *
     * <p>Default: <code>build/descriptors/proto.desc</code></p>
     */
    abstract RegularFileProperty getDescriptorPath()

    /**
     * Output directory for generated Java sources.
     */
    abstract DirectoryProperty getOutputDir()

    /**
     * Directory where exported proto files are stored.
     */
    abstract DirectoryProperty getExportDir()

    /**
     * Extra arguments to pass to buf generate command.
     *
     * <p>Example: <code>['--exclude-path', 'google/']</code></p>
     */
    abstract ListProperty<String> getBufGenerateArgs()

    /**
     * Extra arguments to pass to buf lint command.
     *
     * <p>Example: <code>['--config', 'buf.yaml']</code></p>
     */
    abstract ListProperty<String> getLintArgs()

    /**
     * Reference to compare against for breaking change detection.
     *
     * <p>Can be a BSR reference, git URL, or local directory.</p>
     * <p>Example: <code>'buf.build/pipestreamai/intake'</code> or <code>'../previous-protos'</code></p>
     */
    abstract Property<String> getBreakingAgainstRef()

    /**
     * Extra arguments to pass to buf breaking command.
     */
    abstract ListProperty<String> getBreakingArgs()

    /**
     * Extra arguments to pass to buf format command.
     */
    abstract ListProperty<String> getFormatArgs()

    /**
     * Whether to copy descriptor files to test resource directories.
     *
     * <p>When enabled, descriptors are copied to: <code>build/resources/test/grpc/</code></p>
     *
     * <p>Note: Generated files are only copied to build directories, never to source
     * directories, following Gradle best practices.</p>
     *
     * <p>Default: <code>false</code></p>
     */
    abstract Property<Boolean> getCopyDescriptorsToResources()

    /**
     * Directory for test build resources (classpath approach).
     *
     * <p>Default: <code>build/resources/test/grpc</code></p>
     * <p>This is the only output directory - we never copy to source directories.</p>
     */
    abstract DirectoryProperty getTestBuildDir()

    /**
     * Container of proto modules to fetch and generate.
     */
    NamedDomainObjectContainer<ProtoModule> getModules() {
        return modules
    }

    /**
     * DSL support for modules { } block.
     */
    void modules(Closure closure) {
        modules.configure(closure)
    }

    /**
     * Container of extra buf plugins to include in generation.
     */
    NamedDomainObjectContainer<BufPlugin> getExtraPlugins() {
        return extraPlugins
    }

    /**
     * DSL support for extraPlugins { } block.
     */
    void extraPlugins(Closure closure) {
        extraPlugins.configure(closure)
    }
}
