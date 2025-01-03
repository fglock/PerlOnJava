**Work in Progress**

# Getting Started with PerlOnJava

This guide helps you start using PerlOnJava to run Perl code on the Java Virtual Machine.

## Quick Start

1. Download the JAR file:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar
```

2. Run your first Perl script:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar -E 'print "Hello from Perl on JVM!\n"'
```

## Basic Usage Examples

### 1. Running Scripts

Run a Perl file:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar script.pl
```

Run Perl code directly:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar -E 'for (1..3) { print "$_\n" }'
```

### 2. Using Modules

```perl
use strict;
use warnings;
use JSON;

my $data = {name => 'PerlOnJava', version => '2.1.0'};
print encode_json($data);
```

### 3. Database Access

```perl
use DBI;

my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
$dbh->do("CREATE TABLE test (id INT, name VARCHAR(50))");
$dbh->do("INSERT INTO test VALUES (1, 'PerlOnJava')");

my $sth = $dbh->prepare("SELECT * FROM test");
$sth->execute();
while (my $row = $sth->fetchrow_hashref) {
    print "$row->{id}: $row->{name}\n";
}
```

### 4. File Operations

```perl
use File::Find;
use File::Basename;

find(sub {
    print dirname($File::Find::name), "\n" if -d;
}, ".");
```

### 5. HTTP Requests

```perl
use HTTP::Tiny;

my $response = HTTP::Tiny->new->get('http://example.com/');
print $response->{content} if $response->{success};
```

## Command Line Options

Common switches:
- `-e` - Execute Perl code
- `-E` - Execute Perl code with all features enabled
- `-c` - Check syntax only
- `-I` - Add directory to module search path
- `-M` - Load module before execution

## Development Tools

Enable debugging output:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar --debug script.pl
```

View generated bytecode:
```bash
java -jar target/perlonjava-1.0-SNAPSHOT.jar --disassemble script.pl
```

## Next Steps

- Check [FEATURE_MATRIX.md](../FEATURE_MATRIX.md) for supported features
- See [JDBC_GUIDE.md](JDBC_GUIDE.md) for database connectivity
- Review [DEBUGGING.md](DEBUGGING.md) for troubleshooting
- Visit [SUPPORT.md](../SUPPORT.md) for help and contributing

## Tips and Best Practices

1. Use strict and warnings:
```perl
use strict;
use warnings;
```

2. Leverage Java integration:
```perl
# Access Java classes through JSR 223
use Java;
my $date = Java::java::util::Date->new();
```

3. Take advantage of built-in modules:
```perl
use File::Spec::Functions qw(catfile);
use Cwd qw(getcwd);
my $file = catfile(getcwd(), "data.txt");
```

4. Handle errors properly:
```perl
eval {
    # Your code here
};
if ($@) {
    warn "Error occurred: $@";
}
```

This guide covers the essentials to get you started with PerlOnJava. For more detailed information, refer to the specific guides in the docs directory.
