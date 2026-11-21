# PerlOnJava

> Perl 5 on the JVM — single jar, batteries included

[![Build Status](https://github.com/fglock/PerlOnJava/actions/workflows/gradle.yml/badge.svg?branch=master)](https://github.com/fglock/PerlOnJava/actions)
[![Latest release](https://img.shields.io/github/v/tag/fglock/PerlOnJava?label=release&sort=semver)](https://github.com/fglock/PerlOnJava/releases)
[![License](https://img.shields.io/badge/license-Artistic_1.0_|_GPL_1+-blue.svg)](LICENSE.md)

PerlOnJava compiles Perl to JVM bytecode. Existing Perl scripts can run on
any platform with a JVM, and the runtime includes 150+ bundled modules, a
CPAN client, a PSGI web server, and JDBC database access — all in a single
jar, with no native dependencies beyond Java 24+.

Compatibility is substantial but not complete. Check the
[feature matrix](docs/reference/feature-matrix.md) and the
[bundled-module list](docs/reference/bundled-modules.md) before relying on a
particular language feature or CPAN module. PerlOnJava is an independent
project, not part of the Perl core distribution.

## Features

- **Single jar distribution** — no installation, no dependencies beyond Java
- **Full toolchain** — `jperl`, `jperldoc`, `jcpan`, `jprove`
- **150+ modules included** — [DBI](docs/guides/database-access.md), [Moose / Class::MOP](docs/reference/bundled-modules.md#moose--classmop), [Plack::Handler::Netty](examples/http_server_plack/README.md), HTTP::Tiny, JSON, XML::Parser, YAML, Text::CSV, and [more](docs/reference/bundled-modules.md)
- **Web server (PSGI)** — run web apps with [Plack::Handler::Netty](examples/http_server_plack/README.md)
- **Install more with jcpan** — [pure-Perl CPAN modules](docs/guides/using-cpan-modules.md) work out of the box
- **JDBC database access** — [PostgreSQL, MySQL, SQLite, Oracle](docs/guides/database-access.md) via standard JDBC drivers
- **Perl ithreads** — `threads`, `threads::shared`, `Thread::Queue`, and `Thread::Semaphore` on both backends ([details](docs/reference/threads.md))
- **Embed in Java apps** — [JSR-223 ScriptEngine](docs/guides/java-integration.md) integration
- **Perl 5.44 language compatibility target** — coverage and limitations are tracked in the [feature matrix](docs/reference/feature-matrix.md)

## Quick Start

**Requires:** JDK 24 or later (`java -version`), Git, Make.

```bash
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava
make

./jperl -E 'say "Hello World"'
./jperl -MJSON -E 'say encode_json({hello => "world"})'
```

**[Full Quick Start Guide](docs/getting-started/quickstart.md)** — installation options, database setup, Docker.

## Documentation

| Getting Started | Guides | Reference |
|---|---|---|
| [Installation](docs/getting-started/installation.md) | [Database Access](docs/guides/database-access.md) | [Feature Matrix](docs/reference/feature-matrix.md) |
| [Quick Start](docs/getting-started/quickstart.md) | [Web Server (PSGI)](examples/http_server_plack/README.md) | [CLI Options](docs/reference/cli-options.md) |
| [Docker](docs/getting-started/docker.md) | [Java Integration](docs/guides/java-integration.md) | [Architecture](docs/reference/architecture.md) |
| [One-liners](docs/getting-started/oneliners.md) | [Using CPAN Modules](docs/guides/using-cpan-modules.md) | [Bundled Modules](docs/reference/bundled-modules.md) |
| | [Module Porting](docs/guides/module-porting.md) | [Threads](docs/reference/threads.md) |
| | | [XS Compatibility](docs/reference/xs-compatibility.md) |
| | | [Memory Management](docs/reference/memory-management.md) |
| | | [Testing](docs/reference/testing.md) |

**About:** [Why PerlOnJava?](docs/about/why-perlonjava.md) | [Roadmap](docs/about/roadmap.md#current-project-status) | [Changelog](docs/about/changelog.md) | [Support](docs/about/support.md)

**Project governance:** [Security](SECURITY.md) | [AI Policy](AI_POLICY.md) | [Code of Conduct](CODE_OF_CONDUCT.md)

## Contributing

See **[CONTRIBUTING.md](CONTRIBUTING.md)** for build instructions, code
organization, and how to submit pull requests.

## License

Same terms as Perl 5 — [Artistic License](Artistic) or [GPL v1+](Copying).
See [LICENSE.md](LICENSE.md). Copyright (c) 2024–2026 Flavio Glock.
