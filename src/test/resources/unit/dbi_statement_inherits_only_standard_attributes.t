use strict;
use warnings;
use Test::More tests => 3;
use DBI;

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:', '', '',
    { RaiseError => 1, PrintError => 0 },
);
ok($dbh, 'connected to the in-memory database');

$dbh->{HandleError} = sub { die $_[0] };
my $sth = $dbh->prepare_cached('SELECT 1');
ok($sth, 'prepared a cached statement');
ok(!exists $sth->{CachedKids},
    'statement does not inherit the parent handle statement cache');
