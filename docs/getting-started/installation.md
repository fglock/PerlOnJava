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

## Prerequisites
- Java 21 or higher
- Maven or Gradle
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
- FASTJSON v2: For JSON support
- SnakeYAML Engine: for YAML support

## Running PerlOnJava

### Platform-Specific Instructions

**Unix/Linux/Mac:**
```bash
./jperl -E 'print "Hello World"'
./jperl myscript.pl
CLASSPATH="jdbc-drivers/h2-2.2.224.jar" ./jperl myscript.pl
```

**Windows:**
```bash
jperl -E "print 'Hello World'"
jperl myscript.pl
set CLASSPATH=jdbc-drivers\h2-2.2.224.jar
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
```perl
use DBI;
my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
$dbh->do("CREATE TABLE test (id INT, name VARCHAR(50))");
```

See [Database Access Guide](../guides/database-access.md) for detailed connection examples and supported databases.

## Build Notes
- Maven builds use `maven-shade-plugin` for creating the shaded JAR
- Gradle builds use `com.github.johnrengelman.shadow` plugin
- Both configurations target Java 21

## Java Library Upgrades

**Maven:**

`mvn versions:use-latest-versions`.

**Gradle:**

`./gradlew useLatestVersions`.

## Using Configure.pl

The `Configure.pl` script manages configuration settings and dependencies for PerlOnJava.

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
./Configure.pl --direct com.h2database:h2:2.2.224
make  # Rebuild to include driver
```

**Update configuration:**
```bash
./Configure.pl -D perlVersion=v5.42.0
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
