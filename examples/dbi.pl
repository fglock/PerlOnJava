# misc/snippets/dbi.pl
#
# Install h2 driver:
# curl https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar --output h2-2.2.224.jar
#
# Run using:
# java -cp "h2-2.2.224.jar:target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main examples/dbi.pl
#

use strict;
use warnings;
use DBI;
use Data::Dumper;
use feature 'say';

# Connect to H2 database
my $dbh = DBI->connect(
    "dbi:org.h2.Driver:mem:testdb;DB_CLOSE_DELAY=-1",  # In-memory H2 database
    "sa",                 # Default H2 username
    "",                   # Empty password
    { RaiseError => 1 }
);

# Create a test table
$dbh->do("CREATE TABLE users (
    id INTEGER AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    age INTEGER
)");

# Insert some test data
$dbh->do("INSERT INTO users (name, age) VALUES (?, ?)", 
    undef, "Alice", 30);
$dbh->do("INSERT INTO users (name, age) VALUES (?, ?)", 
    undef, "Bob", 25);
say "last_insert_id ", $dbh->last_insert_id;

# Query with parameters
my $sth = $dbh->prepare("SELECT * FROM users WHERE age > ?");
$sth->execute(20);

# Fetch and display results
while (my @row = $sth->fetchrow_array) {
    print "ID: $row[0], Name: $row[1], Age: $row[2]\n";
}


$sth->execute(20);
while (my $row = $sth->fetchrow_arrayref) {
    print Dumper $row;
}

$sth->execute(20);
while (my $row = $sth->fetchrow_hashref) {
    print Dumper $row;
}

print Dumper $dbh->selectrow_arrayref("SELECT * FROM users WHERE age > ?", undef, 20);
print Dumper $dbh->selectrow_hashref("SELECT * FROM users WHERE age > ?", undef, 20);


# Fetch all rows as array refs
$sth->execute(20);  # Reset the result set
my $all_rows = $sth->fetchall_arrayref();
say "\nAll rows as array refs:";
print Dumper $all_rows;

# Fetch only name and age columns by position
$sth->execute(20);
my $name_age = $sth->fetchall_arrayref([1,2]);
say "\nOnly name and age columns:";
print Dumper $name_age;

# Fetch first 1 row only
$sth->execute(20);
my $first_row = $sth->fetchall_arrayref(undef, 1);
say "\nFirst row only:";
print Dumper $first_row;

# Fetch as hash refs with specific column names
$sth->execute(20);
my $named_cols = $sth->fetchall_arrayref({ NAME => 1, AGE => 1 });
say "\nNamed columns only:";
print Dumper $named_cols;

# Rename columns on the fly using index mapping
$sth->execute(20);
my $renamed = $sth->fetchall_arrayref(\{ 1 => 'person_name', 2 => 'person_age' });
say "\nRenamed columns:";
print Dumper $renamed;



# Select specific columns using 1-based indices
my $column_subset = $dbh->selectall_arrayref(
    "SELECT * FROM users",
    {
        Columns => [2, 3]  # Get name and age columns only
    }
);
say "\nColumn subset using indices:";
print Dumper $column_subset;

# Complex query with joins and hash slice (assuming we add a new table)
$dbh->do("CREATE TABLE orders (
    order_id INTEGER AUTO_INCREMENT PRIMARY KEY,
    user_id INTEGER,
    amount DECIMAL(10,2),
    FOREIGN KEY (user_id) REFERENCES users(id)
)");

$dbh->do("INSERT INTO orders (user_id, amount) VALUES (?, ?)",
    undef, 1, 99.99);
$dbh->do("INSERT INTO orders (user_id, amount) VALUES (?, ?)",
    undef, 1, 150.50);
$dbh->do("INSERT INTO orders (user_id, amount) VALUES (?, ?)",
    undef, 2, 75.25);

my $user_orders = $dbh->selectall_arrayref(
    "SELECT u.name, u.age, COUNT(o.order_id) as order_count, SUM(o.amount) as total_spent
     FROM users u
     LEFT JOIN orders o ON u.id = o.user_id
     GROUP BY u.id, u.name, u.age
     HAVING SUM(o.amount) > ?",
    {
        Slice => {},       # Return as array of hashrefs
        MaxRows => 10      # Limit results
    },
    50.00
);
say "\nComplex join with aggregates:";
print Dumper $user_orders;


# Add a new column that allows NULL
$dbh->do("ALTER TABLE users ADD COLUMN last_login TIMESTAMP NULL");

# Insert rows with NULL values
$dbh->do("INSERT INTO users (name, age, last_login) VALUES (?, ?, ?)",
    undef, "Carol", 28, undef);
$dbh->do("INSERT INTO users (name, age, last_login) VALUES (?, ?, ?)",
    undef, "Dave", 35, '2023-01-01 10:00:00');

# Query including NULL values
my $null_results = $dbh->selectall_arrayref(
    "SELECT name, age, last_login FROM users WHERE last_login IS NULL",
    { Slice => {} }
);
say "\nUsers with NULL last_login:";
print Dumper $null_results;

# Compare NULL vs non-NULL
my $all_logins = $dbh->selectall_arrayref(
    "SELECT name, last_login,
     CASE WHEN last_login IS NULL THEN 'Never logged in'
          ELSE 'Has logged in' END as login_status
     FROM users",
    { Slice => {} }
);
say "\nAll users login status:";
print Dumper $all_logins;



$sth->finish;
$dbh->disconnect;

