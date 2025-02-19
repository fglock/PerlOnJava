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
   - [Upgrade Dependencies](#upgrade-dependencies)

## Prerequisites
- Java 21 or higher
- Maven or Gradle
- Optional: JDBC drivers for database connectivity

## Build Options

### Using Maven
```bash
mvn clean package
```

### Using Gradle
```bash
gradle clean package
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

### Upgrade Dependencies

The `Configure.pl` script provides an option to upgrade your project's dependencies to their latest versions. This can be useful for ensuring that your project uses the most up-to-date libraries.

To upgrade dependencies, use the `--upgrade` option:

```bash
perl ./Configure.pl --upgrade
```

This command will:
- Update Maven dependencies to their latest versions if a `pom.xml` file is present.
- Update Gradle dependencies to their latest versions if a `build.gradle` file is present.

Make sure that Maven and Gradle are installed and accessible in your environment.
