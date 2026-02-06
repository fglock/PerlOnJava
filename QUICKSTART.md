# Quick Start Guide

Get PerlOnJava running in 5 minutes.

## Prerequisites

- **Java 21 or later**
- **Git** for cloning the repository
- **Make** (or use Gradle directly)

Check your Java version:
```bash
java -version  # Should show 21 or higher
```

## Installation

### 1. Clone and Build

```bash
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava
make
```

This compiles the project and runs the fast unit tests (completes in ~30 seconds).

**Build troubleshooting:** See [Installation Guide](docs/getting-started/installation.md)

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

### Step 1: Download JDBC Driver

**⚠️ IMPORTANT:** You must download the appropriate JDBC driver for your database.

**H2 Database (for testing):**
```bash
./Configure.pl --search h2
./Configure.pl --install com.h2database:h2:2.2.224
```

**PostgreSQL:**
```bash
./Configure.pl --search postgresql
./Configure.pl --install org.postgresql:postgresql:42.7.1
```

**MySQL:**
```bash
./Configure.pl --search mysql
./Configure.pl --install com.mysql:mysql-connector-j:8.2.0
```

**Other databases:** See **[Database Access Guide](docs/guides/database-access.md)**

### Step 2: Connect to Database

```perl
use DBI;

# H2 in-memory database (for testing)
my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")
    or die $DBI::errstr;

# Create and query
$dbh->do("CREATE TABLE users (id INT, name VARCHAR(50))");
$dbh->do("INSERT INTO users VALUES (1, 'Alice')");

my $sth = $dbh->prepare("SELECT * FROM users");
$sth->execute();

while (my $row = $sth->fetchrow_hashref) {
    say "$row->{id}: $row->{name}";
}
```

**Full guide:** **[Database Access Guide](docs/guides/database-access.md)**

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
