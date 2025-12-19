# Pipestream Proto Toolchain

[![Maven Central](https://img.shields.io/maven-central/v/ai.pipestream/quarkus-buf-grpc-generator?label=Maven%20Central)](https://central.sonatype.com/artifact/ai.pipestream/quarkus-buf-grpc-generator)
[![GitHub Release](https://img.shields.io/github/v/release/ai-pipestream/quarkus-buf-grpc-generator?label=GitHub%20Release)](https://github.com/ai-pipestream/quarkus-buf-grpc-generator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Build](https://github.com/ai-pipestream/quarkus-buf-grpc-generator/actions/workflows/ci.yml/badge.svg)](https://github.com/ai-pipestream/quarkus-buf-grpc-generator/actions/workflows/ci.yml)

A Gradle plugin for streamlined Protocol Buffer code generation with **100% local execution**. Fetch protos from BSR or Git, generate Java/gRPC/Mutiny stubs—all without uploading data to external servers.

**[Website](https://pipestream.ai)** | **[GitHub](https://github.com/ai-pipestream)** | **[Documentation](#documentation)**

---

## Features

- **Three source modes** - BSR (default), Git (per-module), or Git Workspace (monorepo with cross-module imports)
- **100% local generation** - No proto files uploaded to external servers
- **Hermetic builds** - All binaries downloaded from Maven Central
- **Quarkus Mutiny support** - Reactive gRPC stubs out of the box
- **Zero boilerplate** - Declarative DSL replaces manual task wiring
- **Configuration cache compatible** - Fully compatible with Gradle 9+ configuration cache for faster builds

## Quick Start

Add to your `build.gradle` (check badge above for latest version):

```groovy
plugins {
    id 'java'
    id 'ai.pipestream.proto-toolchain' version '0.7.1'  // See badge for latest
}

repositories {
    mavenCentral()
}

pipestreamProtos {
    modules {
        register("mymodule") {
            bsr = "buf.build/yourorg/yourmodule"
        }
    }
}
```

Build:

```bash
./gradlew build
```

Generated sources appear in `build/generated/source/proto/main/java/`.

## Documentation

| Document | Description |
|----------|-------------|
| **[GUIDE.md](GUIDE.md)** | Complete tutorial with examples and configuration reference |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | Technical architecture and implementation details |
| **[BEFORE_AFTER_EXAMPLE.md](BEFORE_AFTER_EXAMPLE.md)** | Migration guide showing before/after comparison (temporary during migration) |
| **[CONFIGURATION_CACHE.md](CONFIGURATION_CACHE.md)** | Configuration cache compatibility guide and implementation details |

## Building from Source

### Prerequisites

- Java 17 or higher
- Gradle 8.x (wrapper included)

### Build

```bash
# Clone the repository
git clone https://github.com/ai-pipestream/quarkus-buf-grpc-generator.git
cd quarkus-buf-grpc-generator

# Build the plugin
./gradlew build

# Run tests
./gradlew test

# Install to local Maven repository
./gradlew publishToMavenLocal
```

### Using Local Build

After `publishToMavenLocal`, add `mavenLocal()` to your plugin repositories in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

Then use the SNAPSHOT version in your `build.gradle`:

```groovy
plugins {
    id 'ai.pipestream.proto-toolchain' version '0.7.2-SNAPSHOT'
}
```

## Project Structure

```
quarkus-buf-grpc-generator/
├── src/main/groovy/ai/pipestream/proto/
│   ├── ProtoToolchainPlugin.groovy    # Main plugin class
│   ├── ProtoExtension.groovy          # DSL extension
│   ├── ProtoModule.groovy             # Module configuration
│   ├── BinaryResolver.groovy          # Maven binary downloads
│   └── tasks/
│       ├── FetchProtosTask.groovy     # BSR/Git export
│       ├── PrepareGeneratorsTask.groovy # Binary setup
│       ├── GenerateProtosTask.groovy  # Code generation
│       └── BuildDescriptorsTask.groovy # Descriptor generation
├── src/test/groovy/                   # Test suite
├── GUIDE.md                           # User guide
├── ARCHITECTURE.md                    # Architecture docs
├── BEFORE_AFTER_EXAMPLE.md            # Migration guide (temporary)
└── CONFIGURATION_CACHE.md             # Configuration cache compatibility guide
```

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Links

- **Website**: [https://pipestream.ai](https://pipestream.ai)
- **GitHub Organization**: [https://github.com/ai-pipestream](https://github.com/ai-pipestream)
- **Bug Reports**: [GitHub Issues](https://github.com/ai-pipestream/quarkus-buf-grpc-generator/issues)

---

<p align="center">
  Made with care by the <a href="https://pipestream.ai">Pipestream</a> team
</p>
