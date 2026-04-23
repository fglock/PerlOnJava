# Build and Execution Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Build Options](#build-options)
   - [Using Make](#using-make)
   - [Using Maven](#using-maven)
   - [Using Gradle](#using-gradle)
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
- Java 22 or higher (Java 22, 23, 24, 25+ are all supported)
- Maven or Gradle (Gradle wrapper included - recommended)
- Optional: JDBC drivers for database connectivity

## Build Options

### Using Make
The project includes a Makefile that wraps Gradle commands for a familiar build experience:

```bash
make          # same as 'make build'
make build    # builds the project and runs unit tests
make dev      # force clean rebuild (use during active development)
make test     # runs fast unit tests
make clean    # cleans build artifacts
make deb      # creates a Debian package (Linux only)
```

### Using Maven
```bash
mvn clean package
```

### Using Gradle
```bash
./gradlew clean build
```

## Package Installation

### Debian Package

For Debian-based systems (Ubuntu, Debian, Mint, etc.), you can create and install a `.deb` package:

**Build the package:**
```bash
make deb
```

This creates a Debian package in `build/distributions/` with:
- PerlOnJava JAR installed to `/usr/share/perlonjava/`
- `jperl` command installed to `/usr/bin/`
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
./jperl Configure.pl --search mysql-connector-java
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
- Gradle builds use `com.github.johnrengelman.shadow` plugin
- Both configurations target Java 22

## Java Library Upgrades

**Maven:**

`mvn versions:use-latest-versions`.

**Gradle:**

`./gradlew useLatestVersions`.

## Using Configure.pl

The `Configure.pl` script manages configuration settings and dependencies for PerlOnJava.

> **Tip:** Using `./jperl` to run Configure.pl is recommended because it includes
> built-in HTTPS support. System Perl may require additional modules
> (`IO::Socket::SSL`, `Net::SSLeay`) for the Maven Central search to work.

### Common Tasks

**View current configuration:**
```bash
./jperl Configure.pl
```

**Add JDBC driver (search):**
```bash
./jperl Configure.pl --search mysql
make  # Rebuild to include driver
```

**Add JDBC driver (direct):**
```bash
./jperl Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
make  # Rebuild to include driver
```

**Update configuration:**
```bash
./jperl Configure.pl -D perlVersion=v5.42.0
```

**Upgrade all dependencies:**
```bash
./jperl Configure.pl --upgrade
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

### "Unsupported class file major version 69" (Java 25+)

**Problem:** When building with Java 25 or later, you see:
```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 69
> Unsupported class file major version 69
```

**Cause:** An old cached Gradle version (8.x) doesn't support Java 25. Java 25 uses class file version 69, which requires Gradle 9.0+.

**Solution:** Clear the old Gradle cache and rebuild:

```bash
# Linux/macOS
rm -rf ~/.gradle/wrapper/dists/gradle-8.*
make clean
make

# Windows (PowerShell)
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.*"
gradlew.bat clean
make
```

The project includes a Gradle wrapper configured for Gradle 9.0+, which supports Java 22 through Java 26.

### Java Version Compatibility

Based on [Gradle's official compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html):

| Java Version | Class File Version | Gradle Required |
|--------------|-------------------|-----------------|
| Java 22      | 66                | 8.8+            |
| Java 23      | 67                | 8.10+           |
| Java 24      | 68                | 8.14+           |
| Java 25      | 69                | 9.1.0+          |
| Java 26      | 70                | 9.4.0+          |

The Makefile automatically detects Java 25+ and upgrades the Gradle wrapper to 9.1.0 if needed. It also clears any incompatible cached Gradle distributions (8.x and 9.0.x).

### "JAVA_HOME is not set"

Make sure you have a JDK installed and JAVA_HOME is set:

```bash
# Linux/macOS (add to ~/.bashrc or ~/.zshrc)
export JAVA_HOME=/path/to/jdk

# Windows (System Properties > Environment Variables)
set JAVA_HOME=C:\path\to\jdk
```

### Build Takes Too Long

Use `make dev` instead of `make` for faster builds during development - it skips tests:

```bash
make dev  # Compiles only, no tests
```
