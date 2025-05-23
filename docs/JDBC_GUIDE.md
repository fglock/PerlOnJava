# JDBC Database Guide for PerlOnJava

This guide explains how to use databases with PerlOnJava through the DBI module and JDBC drivers.

## Quick Start

```perl
use DBI;

my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
$dbh->do("CREATE TABLE users (id INT, name VARCHAR(50))");
```

## Adding JDBC Drivers

JDBC Database drivers can be added in two ways:

1. Using Configure.pl:
    - The Configure script updates the build configuration to install the JDBC database drivers in the PerlOnJava jar file
    - It uses Maven Central search (https://search.maven.org/) to locate drivers
    - Will prompt for confirmation if multiple matches are found

Examples:
```bash
./Configure.pl --search mysql-connector-java
./Configure.pl --search aws-mysql-jdbc
```

Then build with the drivers included:
```bash
mvn clean package
# or
gradle clean build
```

2. Using Java classpath:
    - Download the JDBC database driver jar file
    - Run your Perl script with the full class path including the JDBC driver:

   Unix/Linux/Mac:
    ```bash
    CLASSPATH="jdbc-drivers/h2-2.2.224.jar" ./jperl myscript.pl
    ```

   Windows:
    ```bash
    set CLASSPATH=jdbc-drivers\h2-2.2.224.jar
    jperl myscript.pl
    ```
   
    Calling java directly with the classpath is also possible:
    ```bash
    java -cp "jdbc-drivers/h2-2.2.224.jar:target/perlonjava-3.0.0.jar" org.perlonjava.Main myscript.pl
    ```

## Database Connection Examples

### H2 Database
```perl
# In-memory database
my $dbh = DBI->connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");

# File-based database
my $dbh = DBI->connect("jdbc:h2:file:/path/to/database");
```

### MySQL
```perl
my $dbh = DBI->connect(
    "jdbc:mysql://localhost/database_name",
    "username",
    "password"
);
```

### PostgreSQL
```perl
my $dbh = DBI->connect(
    "jdbc:postgresql://localhost:5432/database_name",
    "username",
    "password"
);
```

### SQLite
```perl
my $dbh = DBI->connect("jdbc:sqlite:/path/to/database.db");
```

### BigQuery
```perl
my $dbh = DBI->connect(
    "jdbc:bigquery://project_id" .
    ";OAuthType=0" .
    ";OAuthServiceAcctEmail=your-service-account" .
    ";OAuthPvtKeyPath=/path/to/key.json"
);
```

### Snowflake
```perl
my $dbh = DBI->connect(
    "jdbc:snowflake://account-identifier.region.snowflakecomputing.com" .
    "?warehouse=warehouse_name" .
    "&role=role_name" .
    "&db=database_name",
    "username",
    "password"
);
```

### Google Spanner
```perl
my $dbh = DBI->connect(
    "jdbc:cloudspanner://projects/PROJECT_ID/instances/INSTANCE_ID/databases/DATABASE_ID",
    undef,
    undef,
    {
        credentials => "/path/to/credentials.json"
    }
);
```

### Oracle
```perl
my $dbh = DBI->connect(
"jdbc:oracle:thin:@hostname:1521/service_name",
"username",
"password"
);
```

## DBI Methods

### Basic Operations
```perl
# Execute SQL
$dbh->do("INSERT INTO users (id, name) VALUES (?, ?)", undef, 1, "John");

# Prepare and execute
my $sth = $dbh->prepare("SELECT * FROM users WHERE id = ?");
$sth->execute(1);

# Fetch results
while (my $row = $sth->fetchrow_hashref) {
    print "$row->{id}: $row->{name}\n";
}
```

### Transaction Support
```perl
eval {
    $dbh->{AutoCommit} = 0;
    $dbh->do("INSERT INTO users (id, name) VALUES (?, ?)", undef, 1, "John");
    $dbh->do("UPDATE counters SET value = value + 1");
    $dbh->commit;
};
if ($@) {
    warn "Transaction failed: $@";
    $dbh->rollback;
}
```

### Batch Operations
```perl
my $sth = $dbh->prepare("INSERT INTO users (id, name) VALUES (?, ?)");
$sth->execute_array(undef, [1, 2, 3], ["John", "Jane", "Bob"]);
```

## Error Handling

```perl
# Enable automatic error handling
$dbh->{RaiseError} = 1;
$dbh->{PrintError} = 0;

# Manual error handling
if ($dbh->err) {
    die "Database error: " . $dbh->errstr;
}
```

## Best Practices

1. Always check for errors:
```perl
my $dbh = DBI->connect($dsn, $user, $pass, {
    RaiseError => 1,
    PrintError => 0,
    AutoCommit => 1,
});
```

2. Use placeholders for SQL parameters:
```perl
# Good
$sth->execute($user_input);

# Avoid
$dbh->do("SELECT * FROM users WHERE name = '$user_input'");  # SQL injection risk
```

3. Clean up resources:
```perl
$sth->finish;
$dbh->disconnect;
```

4. Use transactions for data consistency:
```perl
$dbh->{AutoCommit} = 0;
eval {
    # Multiple operations
    $dbh->commit;
};
```

## Performance Tips

1. Batch operations for multiple inserts:
```perl
my $sth = $dbh->prepare("INSERT INTO users VALUES (?, ?)");
$dbh->{AutoCommit} = 0;
for my $user (@users) {
    $sth->execute(@$user);
}
$dbh->commit;
```

2. Use appropriate fetch methods:
```perl
# For single row
my $row = $sth->fetchrow_hashref;

# For multiple rows
my $rows = $sth->fetchall_arrayref({});
```

3. Reuse prepared statements:
```perl
my $sth = $dbh->prepare("SELECT * FROM users WHERE id = ?");
for my $id (@ids) {
    $sth->execute($id);
    while (my $row = $sth->fetchrow_hashref) {
        # Process row
    }
}
```

## Supported Features

- Connection management
- Prepared statements
- Transactions
- Result set handling
- Error handling
- Parameter binding
- Batch operations
- Metadata access

## See Also

- [FEATURE_MATRIX.md](FEATURE_MATRIX.md) for complete feature list
- [DBI documentation](https://metacpan.org/pod/DBI) for standard DBI interface
