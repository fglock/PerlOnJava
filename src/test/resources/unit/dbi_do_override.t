use strict;
use warnings;
use Test::More tests => 2;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});

my $called = 0;
{
    no warnings 'redefine';
    *DBI::db::do = sub {
        my ($handle, $statement) = @_;
        $called++;
        return '0E0' if $statement =~ /^SET\b/i;
        return;
    };
}

my $result = $dbh->do('SET SESSION wait_timeout = 1');
is($result, '0E0', 'DBI::db::do override handles the SET statement');

is($called, 1, 'DBI::db::do override was called');
