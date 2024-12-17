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

$sth->finish;
$dbh->disconnect;

