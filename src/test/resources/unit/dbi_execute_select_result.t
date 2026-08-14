use strict;
use warnings;
use Test::More tests => 4;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});

$dbh->do('CREATE TABLE example (value INTEGER)');

my $select = $dbh->prepare('SELECT value FROM example');
is($select->execute, '0E0', 'SELECT execute returns DBI true-zero with no rows');

$dbh->do('INSERT INTO example VALUES (42)');
is($select->execute, '0E0', 'SELECT execute returns DBI true-zero before fetching rows');
is_deeply($select->fetchrow_arrayref, [42], 'SELECT result remains available');

$select->finish;
ok($dbh->disconnect, 'disconnect succeeds');
