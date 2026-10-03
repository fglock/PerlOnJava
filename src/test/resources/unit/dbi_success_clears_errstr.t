use strict;
use warnings;
use Test::More tests => 3;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$dbh->do('CREATE TABLE success_errstr (value INTEGER)');
my $rows = $dbh->do('INSERT INTO success_errstr VALUES (1)');

is($rows, 1, 'successful insert reports its affected row count');
ok(!defined($dbh->errstr), 'successful operation leaves errstr undefined');
ok(!defined($dbh->{errstr}), 'successful operation leaves handle errstr undefined');
