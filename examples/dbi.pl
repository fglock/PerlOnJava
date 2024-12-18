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

$sth->finish;
$dbh->disconnect;

