# Build and Execution Guide

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

## Running PerlOnJava

### Platform-Specific Instructions

Unix/Linux/Mac:
```bash
./jperl -E 'print "Hello World"'
./jperl myscript.pl
CLASSPATH="jdbc-drivers/h2-2.2.224.jar" ./jperl myscript.pl
```

Windows:
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

