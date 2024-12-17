package DBI;
use strict;

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/Dbi.java

# Example:
#
# java -cp "h2-2.2.224.jar:target/perlonjava-1.0-SNAPSHOT.jar" org.perlonjava.Main dbi.pl
#
# # Connect to H2 database
# my $dbh = DBI->connect(
#     "dbi:org.h2.Driver:mem:testdb;DB_CLOSE_DELAY=-1",  # In-memory H2 database
#     "sa",                 # Default H2 username
#     "",                   # Empty password
#     { RaiseError => 1 }
# );

# package DBI::db;
# Class for $dbh

sub do {
    my($dbh, $statement, $attr, @params) = @_;
    my $sth = $dbh->prepare($statement, $attr) or return undef;
    $sth->execute(@params) or return undef;
    my $rows = $sth->rows;
    ($rows == 0) ? "0E0" : $rows;
}

sub finish {
    # placeholder
}

1;