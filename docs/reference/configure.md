# Configure.pl Reference

The `Configure.pl` script manages configuration settings and dependencies for PerlOnJava.

## Synopsis

```bash
./jperl Configure.pl [options]
./jperl Configure.pl -D key=value
./jperl Configure.pl --search keyword
./jperl Configure.pl --direct group:artifact:version
./jperl Configure.pl --upgrade
```

> **Tip:** `Configure.pl` can be run with either `./jperl` or `perl`. Using `./jperl` is
> recommended because it includes built-in HTTPS support, while system Perl may
> require additional modules (`IO::Socket::SSL`, `Net::SSLeay`) for the Maven
> Central search to work.

## Options

### Help and Information

**`-h, --help`**
- Show help message and usage instructions

```bash
./jperl Configure.pl --help
```

### Configuration Management

**`-D key=value`**
- Set configuration values in `Configuration.java`
- Can specify multiple key-value pairs
- String values are automatically quoted
- Boolean/numeric values are used as-is

```bash
./jperl Configure.pl -D perlVersion=v5.40.0
./jperl Configure.pl -D jarVersion=3.0.1
./jperl Configure.pl -D strict_mode=true -D enable_optimizations=false
```

**Special behavior for `jarVersion`:**
- Automatically updates all references to the JAR filename throughout the repository
- Updates from `perlonjava-OLD.jar` to `perlonjava-NEW.jar` in all text files

**View current configuration:**
```bash
./jperl Configure.pl
```

### Dependency Management

**`--search keyword`**
- Search Maven Central for artifacts by keyword, class name, or group:artifact
- Interactive selection if multiple matches found
- Useful for finding JDBC drivers and other libraries

```bash
# Search by keyword
./jperl Configure.pl --search mysql
./jperl Configure.pl --search postgresql

# Search by driver class name
./jperl Configure.pl --search com.mysql.cj.jdbc.Driver
./jperl Configure.pl --search org.postgresql.Driver

# Search by group:artifact
./jperl Configure.pl --search org.postgresql:postgresql
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
./jperl Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
./jperl Configure.pl --direct org.postgresql:postgresql:42.7.1
```

**`--verbose`**
- Enable verbose output for debugging
- Shows Maven Central API URLs
- Displays full search results as JSON
- Useful for troubleshooting search issues

```bash
./jperl Configure.pl --search mysql --verbose
```

### Dependency Upgrades

**`--upgrade`**
- Upgrade all project dependencies to their latest versions
- Updates both Maven (`pom.xml`) and Gradle (`build.gradle`) dependencies
- Uses `mvn versions:use-latest-versions` for Maven
- Uses `./gradlew versionCatalogUpdate` for Gradle

```bash
./jperl Configure.pl --upgrade
```

**Requirements:**
- Maven must be installed for Maven upgrades
- Gradle wrapper must be present for Gradle upgrades

## Workflow

### Adding JDBC Drivers

**Recommended workflow:**

1. Search for the driver:
```bash
./jperl Configure.pl --search mysql-connector-java
```

2. Or use direct coordinates if you know them:
```bash
./jperl Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
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

1. Reads `src/main/java/org/perlonjava/Configuration.java`
2. Finds `public static final Type key = value;` declarations
3. Replaces value with new value
4. Writes updated file back

For `jarVersion` updates, also:
- Scans all text files in the repository
- Replaces old JAR filename with new one
- Skips binary files and hidden directories

## Examples

### View Current Configuration

```bash
./jperl Configure.pl
```

Output:
```
Current configuration:

perlVersion = "v5.40.0"
jarVersion = "3.0.1"
strict_mode = true
```

### Update Configuration

```bash
./jperl Configure.pl -D perlVersion=v5.42.0 -D jarVersion=3.1.0
```

### Search and Add JDBC Driver

```bash
# Search for PostgreSQL driver
./jperl Configure.pl --search postgresql

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
./jperl Configure.pl --direct com.mysql:mysql-connector-j:8.2.0

# Rebuild
make
```

### Upgrade All Dependencies

```bash
./jperl Configure.pl --upgrade
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
./jperl Configure.pl --search mysql
make

# Option 2: Direct coordinates
./jperl Configure.pl --direct com.mysql:mysql-connector-j:8.2.0
make

# Option 3: Manual CLASSPATH (no rebuild needed)
CLASSPATH=/path/to/mysql-connector.jar ./jperl script.pl
```

### Updating Project Version

```bash
./jperl Configure.pl -D jarVersion=4.0.0
# This updates Configuration.java and all references to perlonjava-*.jar
```

### Finding Available Drivers

```bash
# Search by database name
./jperl Configure.pl --search postgresql --verbose

# Search by driver class
./jperl Configure.pl --search org.postgresql.Driver --verbose
```

## Troubleshooting

### Search Returns No Results

**Problem**: `./jperl Configure.pl --search keyword` finds nothing

**Solutions**:
- Try broader keywords: `mysql` instead of `mysql-connector-java-8.2.0`
- Search by driver class: `./jperl Configure.pl --search com.mysql.cj.jdbc.Driver`
- Use `--verbose` to see search URL and results
- Use `--direct` if you know the exact coordinates

### Dependencies Not Found After Adding

**Problem**: Added dependency with Configure.pl but not available at runtime

**Solution**: You must rebuild after adding dependencies:
```bash
./jperl Configure.pl --direct group:artifact:version
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
