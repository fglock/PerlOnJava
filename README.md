# PerlOnJava

> Perl 5 on the JVM — single jar, batteries included

[![Build Status](https://github.com/fglock/PerlOnJava/workflows/Java%20CI%20with%20Gradle/badge.svg)](https://github.com/fglock/PerlOnJava/actions)
[![License](https://img.shields.io/badge/license-Artistic_1.0_|_GPL_1+-blue.svg)](LICENSE.md)

PerlOnJava compiles Perl to JVM bytecode — run existing Perl scripts on any platform with a JVM, with access to Java libraries. One jar file runs on Linux, macOS, and Windows — just add Java 22+. PerlOnJava is an independent project, not part of the Perl core distribution.

## Features

- **Single jar distribution** — no installation, no dependencies beyond Java
- **Full toolchain** — `jperl`, `jperldoc`, `jcpan`, `jprove`
- **150+ modules included** — [DBI](docs/guides/database-access.md), HTTP::Tiny, JSON, XML::Parser, YAML, Text::CSV, and [more](docs/reference/bundled-modules.md)
- **Install more with jcpan** — [pure-Perl CPAN modules](docs/guides/using-cpan-modules.md) work out of the box
- **JDBC database access** — [PostgreSQL, MySQL, SQLite, Oracle](docs/guides/database-access.md) via standard JDBC drivers
- **Embed in Java apps** — [JSR-223 ScriptEngine](docs/guides/java-integration.md) integration
- **Perl 5.42 language compatibility** — [see feature matrix](docs/reference/feature-matrix.md)

## Quick Start

```bash
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava
make

./jperl -E 'say "Hello World"'
./jperl -MJSON -E 'say encode_json({hello => "world"})'
```

**[Full Quick Start Guide](QUICKSTART.md)** — Installation options, database setup, Docker

## Documentation

| Getting Started | Guides | Reference |
|-----------------|--------|-----------|
| [Installation](docs/getting-started/installation.md) | [Database Access](docs/guides/database-access.md) | [Feature Matrix](docs/reference/feature-matrix.md) |
| [Quick Start](QUICKSTART.md) | [Java Integration](docs/guides/java-integration.md) | [CLI Options](docs/reference/cli-options.md) |
| [Docker](docs/getting-started/docker.md) | [Using CPAN Modules](docs/guides/using-cpan-modules.md) | [Architecture](docs/reference/architecture.md) |
| [One-liners](docs/getting-started/oneliners.md) | [Module Porting](docs/guides/module-porting.md) | [Testing](docs/reference/testing.md) |
| | | [Bundled Modules](docs/reference/bundled-modules.md) |

**About:** [Why PerlOnJava?](docs/about/why-perlonjava.md) | [Roadmap](docs/about/roadmap.md) | [Changelog](docs/about/changelog.md) | [Support](docs/about/support.md) | [Security](SECURITY.md) | [AI Policy](AI_POLICY.md)

## About Perl

[Perl](https://www.perl.org/) is a high-level, general-purpose language known for text processing, system administration, and web development. **Learn more:** [www.perl.org](https://www.perl.org/)

## Contributing

See **[CONTRIBUTING.md](CONTRIBUTING.md)** for build instructions, code organization, and how to submit pull requests.

## License

Same terms as Perl 5 — [Artistic License](Artistic) or [GPL v1+](Copying). See [LICENSE.md](LICENSE.md). Copyright (c) Flavio Glock
