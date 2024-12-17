package DBI;

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

1;