# PerlOnJava

> Perl running natively on the JVM

[![Build Status](https://github.com/fglock/PerlOnJava/workflows/CI/badge.svg)](https://github.com/fglock/PerlOnJava/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE.md)

## What is PerlOnJava?

A Perl compiler and runtime for the JVM that:
- Compiles Perl scripts to Java bytecode
- Integrates with Java ecosystems (JDBC, Maven, Spring)
- Supports most Perl 5.42 features
- Includes 150+ core Perl modules (DBI, HTTP::Tiny, JSON, YAML, Text::CSV)

## Quick Start

```bash
# Build
make

# Run Perl
./jperl -E 'say "Hello World"'

# Database access with JDBC
use DBI;
my $dbh = DBI->connect("jdbc:h2:mem:testdb");
```

**→ [Full Quick Start Guide](QUICKSTART.md)**

## Documentation

### Getting Started
- **[Installation](docs/getting-started/installation.md)** - Build and setup
- **[Quick Start](QUICKSTART.md)** - Get running in 5 minutes
- **[Docker](docs/getting-started/docker.md)** - Run in containers
- **[One-liners](docs/getting-started/oneliners.md)** - Quick examples

### Guides
- **[Database Access](docs/guides/database-access.md)** - Using DBI with JDBC drivers
- **[Java Integration](docs/guides/java-integration.md)** - Call Perl from Java (JSR-223)
- **[Module Porting](docs/guides/module-porting.md)** - Port Perl modules
- **[GraalVM](docs/guides/graalvm.md)** - Native compilation

### Reference
- **[Feature Matrix](docs/reference/feature-matrix.md)** - What's implemented
- **[Testing](docs/reference/testing.md)** - Test suite information
- **[Architecture](docs/reference/architecture.md)** - How it works
- **[CLI Options](docs/reference/cli-options.md)** - Command-line reference

### About
- **[Why PerlOnJava?](docs/about/why-perlonjava.md)** - Project goals and use cases
- **[Roadmap](docs/about/roadmap.md)** - Future plans
- **[Changelog](docs/about/changelog.md)** - Release history
- **[Support](docs/about/support.md)** - Get help and contribute
- **[Resources](docs/about/resources.md)** - External links

## Contributing

We welcome contributions! See **[CONTRIBUTING.md](CONTRIBUTING.md)** for:
- How to build and test
- Code organization
- Submitting pull requests
- Developer documentation

## License

[MIT License](LICENSE.md) - Copyright (c) Flavio Glock
