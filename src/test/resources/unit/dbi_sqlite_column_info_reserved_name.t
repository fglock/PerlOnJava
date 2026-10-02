use strict;
use warnings;
use Test::More tests => 1;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$dbh->do('CREATE TABLE "order" (id INTEGER PRIMARY KEY, title TEXT)');

my $sth = $dbh->column_info(undef, undef, 'order', undef);
my $columns = $sth->fetchall_arrayref({});
is_deeply(
    [map { $_->{COLUMN_NAME} } @$columns],
    [qw(id title)],
    'column_info handles a reserved SQLite table name',
);
