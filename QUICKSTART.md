# Quick Start Guide

Get PerlOnJava running in 5 minutes.

## Prerequisites

- **Java Development Kit (JDK) 22 or later**
- **Git** for cloning the repository
- **Make** (contributors should always use `make`; see [CONTRIBUTING.md](CONTRIBUTING.md))

### Verify You Have JDK Installed

Check your Java version:
```bash
java -version
```
Should show version 22 or higher.

**Important:** Check you have the **JDK** (not just JRE):
```bash
javac -version
```
Should show the same version. If `javac: command not found`, you need to install a JDK.

**Installing JDK:**
- Use your system's package manager, or
- Download from a JDK provider (Adoptium, Oracle, Azul, Amazon Corretto, etc.)
- Common package manager commands:
  - **macOS**: `brew install openjdk@22`
  - **Ubuntu/Debian**: `sudo apt install openjdk-22-jdk`
  - **Windows**: Use package manager like [Chocolatey](https://chocolatey.org/) or [Scoop](https://scoop.sh/)

## Installation

### 1. Clone and Build

```bash
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava
make
```

The `make` command builds the project and runs all unit tests. The complete build with tests typically completes in ~30 seconds.

**Build troubleshooting:**

<details>
<summary><strong>"Unsupported class file major version 69" error (Java 25+)</strong></summary>

If you see this error:
```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 69
> Unsupported class file major version 69
```

This means you have Java 25+ but an old cached Gradle version that doesn't support it. Fix by clearing the old Gradle cache:

```bash
# Linux/macOS
rm -rf ~/.gradle/wrapper/dists/gradle-8.*

# Windows (PowerShell)
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.*"

# Then rebuild
make clean
make
```

The project uses Gradle 9.0+ (configured in the wrapper) which supports Java 22-25+.
</details>

For more troubleshooting: See [Installation Guide](docs/getting-started/installation.md#troubleshooting)

**Debian/Ubuntu users:** You can also build and install a `.deb` package:
```bash
make deb
sudo dpkg -i build/distributions/perlonjava_*.deb
```
This installs `jperl` systemwide. See [Installation Guide](docs/getting-started/installation.md#debian-package) for details.

### 2. Verify Installation

<details open>
<summary>Linux/Mac</summary>

```bash
./jperl -E 'say "Hello from PerlOnJava!"'
./jperl -v  # Show version
```
</details>

<details>
<summary>Windows</summary>

```bash
jperl -E "say 'Hello from PerlOnJava!'"
jperl -v  # Show version
```
</details>

## Basic Usage

### Run a Perl Script

```bash
# One-liner
./jperl -E 'for (1..5) { say "Count: $_" }'

# Script file
echo 'use strict; use warnings; say "It works!";' > test.pl
./jperl test.pl
```

### Use Core Modules

```bash
./jperl -MJSON -E 'say encode_json({hello => "world"})'
./jperl -MYAML::PP -E 'say Dump({foo => "bar"})'
./jperl -MData::Dumper -E 'print Dumper [1,2,3]'
```

### More Examples

See **[One-liners Guide](docs/getting-started/oneliners.md)** for practical examples.

## Database Access with DBI

PerlOnJava includes the DBI module with JDBC support.

### Quick Example

**1. Download a JDBC driver** (H2 database for testing):
```bash
wget https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
```

**2. Set CLASSPATH and run:**
```bash
export CLASSPATH=/path/to/h2-2.2.224.jar
./jperl your_script.pl
```

**3. Use DBI in your script:**
```perl
use DBI;

my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")
    or die $DBI::errstr;

$dbh->do("CREATE TABLE users (id INT, name VARCHAR(50))");
$dbh->do("INSERT INTO users VALUES (1, 'Alice')");

my $sth = $dbh->prepare("SELECT * FROM users");
$sth->execute();

while (my $row = $sth->fetchrow_hashref) {
    say "$row->{id}: $row->{name}";
}
```

**→ For PostgreSQL, MySQL, and other databases:** See **[Database Access Guide](docs/guides/database-access.md)**

## Using Perl from Java

PerlOnJava implements JSR-223 (Java Scripting API):

```java
import javax.script.*;

public class TestPerl {
    public static void main(String[] args) throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("perl");

        // Execute Perl code
        engine.eval("print 'Hello from Java!\\n'");

        // Pass variables
        engine.put("name", "World");
        engine.eval("say \"Hello, $name!\"");

        // Get results
        Object result = engine.eval("2 + 2");
        System.out.println("Result: " + result);
    }
}
```

**Full guide:** **[Java Integration Guide](docs/guides/java-integration.md)**

## Running in Docker

Quick start with Docker:

```bash
# Build image
docker build -t perlonjava .

# Run container
docker run -it perlonjava ./jperl -E 'say "Hello from Docker!"'
```

**Full guide:** **[Docker Guide](docs/getting-started/docker.md)**

## Next Steps

### Learn More
- **[Feature Matrix](docs/reference/feature-matrix.md)** - See what Perl features are supported
- **[One-liners](docs/getting-started/oneliners.md)** - Practical Perl examples
- **[Module Porting](docs/guides/module-porting.md)** - Port your favorite Perl modules

### Get Help
- **[Support](docs/about/support.md)** - Community resources
- **[GitHub Issues](https://github.com/fglock/PerlOnJava/issues)** - Report bugs

### Contribute
- **[CONTRIBUTING.md](CONTRIBUTING.md)** - How to contribute
- **[Roadmap](docs/about/roadmap.md)** - Future plans
