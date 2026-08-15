# Configure.pl Reference

The `Configure.pl` script manages configuration settings and dependencies for PerlOnJava.

## Synopsis

```bash
./Configure.pl [options]
./Configure.pl -D key=value
./Configure.pl --search keyword
./Configure.pl --direct group:artifact:version
./Configure.pl --upgrade
```

Run the script directly from the repository root. Its shebang selects the system
Perl, and its required Perl modules are listed at the top of the script.

## Runtime thread configuration

`Configure.pl` does not toggle Perl thread support. The bundled Perl `Config`
module reports the shipped runtime capabilities directly:

| Key | Value | Meaning |
|---|---|---|
| `useithreads` | `define` | Snapshot-based Perl interpreter threads are available. |
| `usethreads` | `define` | The supported Perl thread API is enabled. |
| `usemultiplicity` | `define` | Independent `PerlRuntime` instances are supported in one JVM. |

Java 24 virtual threads are the launcher default. The platform executor remains
available process-wide with `JPERL_THREAD_MODE=platform` or the JVM property
`-Djperl.thread.mode=platform`. Unknown values are rejected. See
[CLI Options](cli-options.md#thread-execution-mode) and the
[feature matrix](feature-matrix.md#concurrency-and-perl-threads).

Runtime pooling is independently opt-in. `JPERL_RUNTIME_POOL_SIZE=N` (or
`-Djperl.runtime.pool.size=N`) enables N prepared PSGI application snapshots;
the default `0` retains the single-runtime handler.

## Options

### Help and Information

**`-h, --help`**
- Show help message and usage instructions

```bash
./Configure.pl --help
```

### Configuration Management

**`-D key=value`**
- Set the project version in `Configuration.java.in`
- String values are automatically quoted

```bash
./Configure.pl -D version=5.44.0
```

**Special behavior for `version`:**
- Automatically updates all references to the JAR filename throughout the repository
- Updates from `perlonjava-OLD.jar` to `perlonjava-NEW.jar` in all text files

**View current configuration:**
```bash
./Configure.pl
```

### Dependency Management

**`--search keyword`**
- Search Maven Central for artifacts by keyword, class name, or group:artifact
- Interactive selection if multiple matches found
- Useful for finding JDBC drivers and other libraries

```bash
# Search by keyword
./Configure.pl --search mysql
./Configure.pl --search postgresql

# Search by driver class name
./Configure.pl --search com.mysql.cj.jdbc.Driver
./Configure.pl --search org.postgresql.Driver

# Search by group:artifact
./Configure.pl --search org.postgresql:postgresql
```

**Search behavior:**
- Class names (ending in `.Driver`): Searches by fully qualified class name
- Keywords with `:`: Searches by `group:artifact` pattern
- Other keywords: Searches in artifact name and text fields
- Returns top 10 most relevant results ranked by JDBC relevance
- Prompts for selection if multiple matches found

**`--direct group:artifact:version`**
- Add dependency using direct Maven coordinates
- No search required - immediately updates build files
- Format must be: `group:artifact:version`

```bash
./Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
./Configure.pl --direct org.postgresql:postgresql:42.7.1
```

**`--verbose`**
- Enable verbose output for debugging
- Shows Maven Central API URLs
- Displays full search results as JSON
- Useful for troubleshooting search issues

```bash
./Configure.pl --search mysql --verbose
```

### Dependency Upgrades

**`--upgrade`**
- Upgrade all project dependencies to their latest versions
- Updates both Maven (`pom.xml`) and Gradle (`build.gradle`) dependencies
- Uses `mvn versions:use-latest-versions` for Maven
- Uses `./gradlew versionCatalogUpdate` for Gradle

```bash
./Configure.pl --upgrade
```

**Requirements:**
- Maven must be installed for Maven upgrades
- Gradle wrapper must be present for Gradle upgrades

## Workflow

### Adding JDBC Drivers

**Recommended workflow:**

1. Search for the driver:
```bash
./Configure.pl --search mysql-connector-java
```

2. Or use direct coordinates if you know them:
```bash
./Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
```

3. Rebuild the project to include the driver:
```bash
make
```

The driver is now bundled in the PerlOnJava JAR.

**Alternative: Manual CLASSPATH**

Instead of bundling drivers, you can load them at runtime:

```bash
# Download driver manually
wget https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.2.0/mysql-connector-j-8.2.0.jar

# Use with CLASSPATH
CLASSPATH=/path/to/mysql-connector-j-8.2.0.jar ./jperl script.pl
```

## How It Works

### Dependency Management

When you add a dependency with `--search` or `--direct`:

1. **Updates `build.gradle`** (if present):
   - Adds `implementation "group:artifact:version"` to dependencies block

2. **Updates `pom.xml`** (if present):
   - Adds `<dependency>` entry with groupId, artifactId, version

3. **Requires rebuild**:
   - Run `make` or `./gradlew build` to download and bundle the dependency

### Search Ranking

The Maven Central search ranks results by JDBC relevance:

- **+5 points**: `jdbc` in group or artifact name
- **+4 points**: Class name ends with `Driver`
- **+3 points**: Database keywords (mysql, postgresql, oracle, sqlserver, database)
- **+2 points**: `jdbc` in version
- **Bonus**: Logarithm of download count

This ensures JDBC drivers appear first in search results.

### Configuration Updates

When you set configuration with `-D`:

1. Reads `src/main/java/org/perlonjava/core/Configuration.java.in`
2. Finds `public static final Type key = value;` declarations
3. Replaces value with new value
4. Writes updated file back

For `version` updates, also:
- Scans all text files in the repository
- Replaces old JAR filename with new one
- Skips binary files and hidden directories

## Examples

### View Current Configuration

```bash
./Configure.pl
```

Output:
```
Current configuration:

version = "5.44.0"
```

### Update Configuration

```bash
./Configure.pl -D version=5.44.0
```

### Search and Add JDBC Driver

```bash
# Search for PostgreSQL driver
./Configure.pl --search postgresql

# Output shows:
# Multiple matches found:
# [0] org.postgresql:postgresql:42.7.1
# [1] com.impossibl.pgjdbc-ng:pgjdbc-ng:0.8.9
# [2] ...
# Select number [0-9]: 0

# Updates build.gradle and pom.xml
# Updated build.gradle
# Updated pom.xml

# Rebuild to include driver
make
```

### Add Driver with Direct Coordinates

```bash
# Add MySQL database driver
./Configure.pl --direct com.mysql:mysql-connector-j:8.2.0

# Rebuild
make
```

### Upgrade All Dependencies

```bash
./Configure.pl --upgrade
```

Output:
```
Upgrading project dependencies to latest versions...
Updating Maven dependencies to latest versions...
Maven dependencies updated successfully.
Updating Gradle dependencies to latest versions using version catalog...
Gradle dependencies updated successfully.
```

## Common Use Cases

### Adding a Database Driver

```bash
# Option 1: Search and select
./Configure.pl --search mysql
make

# Option 2: Direct coordinates
./Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
make

# Option 3: Manual CLASSPATH (no rebuild needed)
CLASSPATH=/path/to/mysql-connector.jar ./jperl script.pl
```

### Updating Project Version

```bash
./Configure.pl -D version=5.44.0
# This updates Configuration.java.in and all references to perlonjava-*.jar
```

### Finding Available Drivers

```bash
# Search by database name
./Configure.pl --search postgresql --verbose

# Search by driver class
./Configure.pl --search org.postgresql.Driver --verbose
```

## Troubleshooting

### Search Returns No Results

**Problem**: `./Configure.pl --search keyword` finds nothing

**Solutions**:
- Try broader keywords: `mysql` instead of `mysql-connector-java-8.2.0`
- Search by driver class: `./Configure.pl --search com.mysql.cj.jdbc.Driver`
- Use `--verbose` to see search URL and results
- Use `--direct` if you know the exact coordinates

### Dependencies Not Found After Adding

**Problem**: Added dependency with Configure.pl but not available at runtime

**Solution**: You must rebuild after adding dependencies:
```bash
./Configure.pl --direct group:artifact:version
make  # This downloads and bundles the dependency
```

### Version Conflicts

**Problem**: Multiple versions of same library

**Solution**: Edit `build.gradle` or `pom.xml` manually to resolve conflicts, or use:
```bash
./gradlew dependencies  # Show dependency tree
mvn dependency:tree     # Show Maven dependency tree
```

## See Also

- **[Installation Guide](../getting-started/installation.md)** - Build and setup
- **[Database Access Guide](../guides/database-access.md)** - Using JDBC drivers
- **[CLI Options](cli-options.md)** - jperl command-line options
