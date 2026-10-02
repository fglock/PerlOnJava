use strict;
use warnings;
use Test::More tests => 2;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$dbh->do('CREATE TABLE active_metadata (id INTEGER PRIMARY KEY, name TEXT)');

my $sth = $dbh->column_info(undef, undef, 'active_metadata', undef);
my $columns = $sth->fetchall_arrayref({});
is(ref($columns), 'ARRAY', 'column_info fetchall_arrayref returns an array');
is_deeply(
    [map { $_->{COLUMN_NAME} } @$columns],
    [qw(id name)],
    'column_info returns all SQLite columns',
);
