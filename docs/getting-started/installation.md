# Build and Execution Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Build Options](#build-options)
   - [Using Maven](#using-maven)
   - [Using Gradle](#using-gradle)
3. [Dependencies](#dependencies)
4. [Running PerlOnJava](#running-perlonjava)
   - [Platform-Specific Instructions](#platform-specific-instructions)
   - [Common Options](#common-options)
5. [Database Integration](#database-integration)
   - [Adding JDBC Drivers](#adding-jdbc-drivers)
   - [Database Connection Example](#database-connection-example)
6. [Build Notes](#build-notes)
7. [Java Library Upgrades](#java-library-upgrades)
8. [Using Configure.pl](#using-configurepl)
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
make build    # builds the project
make test     # runs tests
make clean    # cleans build artifacts
make deb      # creates a Debian package
```

### Using Maven
```bash
mvn clean package
```

### Using Gradle
```bash
./gradlew clean build
```

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

See [JDBC Database Guide](JDBC_GUIDE.md) for detailed connection examples and supported databases.

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
