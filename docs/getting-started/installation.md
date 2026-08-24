# Build and Execution Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Building from Source](#building-from-source)
3. [Package Installation](#package-installation)
   - [Debian Package](#debian-package)
4. [Dependencies](#dependencies)
5. [Running PerlOnJava](#running-perlonjava)
   - [Platform-Specific Instructions](#platform-specific-instructions)
   - [Common Options](#common-options)
6. [Database Integration](#database-integration)
   - [Adding JDBC Drivers](#adding-jdbc-drivers)
   - [Database Connection Example](#database-connection-example)
7. [Build Notes](#build-notes)
8. [Java Library Upgrades](#java-library-upgrades)
9. [Using Configure.pl](#using-configurepl)
   - [Common Tasks](#common-tasks)
   - [Available Options](#available-options)
   - [Important Notes](#important-notes)
10. [Troubleshooting](#troubleshooting)

## Prerequisites
- JDK 24 or later
- Make and the included Gradle wrapper
- Optional: JDBC drivers for database connectivity

## Building from Source

Use the project Makefile for source builds. It applies the repository's tested
Gradle configuration consistently:

```bash
make          # same as 'make build'
make build    # builds the project and runs unit tests
make test     # runs fast unit tests
make clean    # cleans build artifacts
make deb      # creates a Debian package (Linux only)
```

## Package Installation

### Debian Package

For Debian-based systems (Ubuntu, Debian, Mint, etc.), you can create and install a `.deb` package:

**Build the package:**
```bash
make deb
```

This creates a Debian package in `build/distributions/` with:
- PerlOnJava installed under `/opt/perlonjava/`
- `jperl`, `jcpan`, `jperldoc`, and `jprove` linked into `/usr/local/bin/`
- All dependencies bundled
- Systemwide availability

**Install the package:**
```bash
sudo dpkg -i build/distributions/perlonjava_*.deb
```

**Usage after installation:**
```bash
# jperl is now available systemwide
jperl -E 'say "Hello World"'
jperl myscript.pl

# No need for ./jperl - it's in your PATH
```

**Uninstall:**
```bash
sudo dpkg -r perlonjava
```

**Benefits of Debian package:**
- Clean installation and removal
- Systemwide availability (no need for `./jperl`)
- Automatic dependency tracking
- Integrates with system package manager
- Can be distributed to other Debian-based systems

## Dependencies
- JUnit: For testing
- ASM: For bytecode manipulation
- ICU4J: For Unicode support
- SnakeYAML Engine: for YAML support

## Running PerlOnJava

### Platform-Specific Instructions

**Unix/Linux/Mac:**
```bash
./jperl -E 'print "Hello World"'
./jperl myscript.pl
```

**Windows:**
```bash
jperl -E "print 'Hello World'"
jperl myscript.pl
```

### Common Options
- `-I lib`: Add library path
- `--debug`: Enable debug output
- `--help`: Show all options

## Database Integration

### Adding JDBC Drivers

1. Using Configure.pl:
```bash
./Configure.pl --search mysql-connector-java
```

2. Using Java classpath (shown in platform-specific examples above)

### Database Connection Example

SQLite is bundled with PerlOnJava — no additional installation needed:

```perl
use DBI;
my $dbh = DBI->connect("dbi:SQLite:dbname=:memory:", "", "");
$dbh->do("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)");
```

For other databases, add JDBC drivers via CLASSPATH or Configure.pl (see below).

See [Database Access Guide](../guides/database-access.md) for detailed connection examples and supported databases.

## Build Notes
- Maven builds use `maven-shade-plugin` for creating the shaded JAR
- Gradle builds use the Shadow plugin
- Both configurations target Java 24

## Java Library Upgrades

**Maven:**

`mvn versions:use-latest-versions`.

**Gradle:**

`./gradlew useLatestVersions`.

## Using Configure.pl

The `Configure.pl` script manages configuration settings and dependencies for PerlOnJava.

Run `Configure.pl` directly from the repository root. It uses the system Perl
specified by its shebang and requires the Perl modules imported at the top of the
script.

### Common Tasks

**View current configuration:**
```bash
./Configure.pl
```

**Add JDBC driver (search):**
```bash
./Configure.pl --search mysql
make  # Rebuild to include driver
```

**Add JDBC driver (direct):**
```bash
./Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
make  # Rebuild to include driver
```

**Update configuration:**
```bash
./Configure.pl -D version=5.44.1
```

**Upgrade all dependencies:**
```bash
./Configure.pl --upgrade
```

### Available Options

- **`-h, --help`** - Show help message
- **`-D key=value`** - Set configuration value
- **`--search keyword`** - Search Maven Central for artifacts
- **`--direct group:artifact:version`** - Add dependency with Maven coordinates
- **`--verbose`** - Enable verbose output
- **`--upgrade`** - Upgrade dependencies to latest versions

### Important Notes

1. **Rebuild required**: After adding dependencies with `--search` or `--direct`, you must run `make` to download and bundle them
2. **Alternative approach**: Instead of bundling drivers, you can use CLASSPATH:
   ```bash
   CLASSPATH=/path/to/driver.jar ./jperl script.pl
   ```

**→ See [Configure.pl Reference](../reference/configure.md) for complete documentation**

## Troubleshooting

### "Unsupported class file major version" errors

**Problem:** When building with Java 25 or later, you see:
```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 69
> Unsupported class file major version 69
```

**Cause:** The build is using an older Gradle installation that cannot run on
your JDK.

**Solution:** Use the repository's Gradle wrapper instead of a system-installed
Gradle. Confirm the configured version, then rebuild:

```bash
./gradlew --version
make wrapper
make clean
make
```

On Windows, use `gradlew.bat --version`. The wrapper version is defined in
`gradle/wrapper/gradle-wrapper.properties`; that file is the source of truth.

### Java Version Compatibility

PerlOnJava compiles for Java 24 and requires JDK 24 or later. Use the included
wrapper so the Gradle version stays aligned with the project. For compatibility
details beyond the checked-in wrapper, consult Gradle's
[Java compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html).

### "JAVA_HOME is not set"

Make sure you have a JDK installed and JAVA_HOME is set:

```bash
# Linux/macOS (add to ~/.bashrc or ~/.zshrc)
export JAVA_HOME=/path/to/jdk

# Windows (System Properties > Environment Variables)
set JAVA_HOME=C:\path\to\jdk
```

### Build Takes Too Long

The default `make` target builds the runnable JAR and runs the fast unit suite.
For a targeted test during development, run the relevant `.t` file with
`./jperl`, then run `make` before committing.
