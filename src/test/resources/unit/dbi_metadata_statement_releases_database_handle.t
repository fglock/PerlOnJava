use strict;
use warnings;
use DBI;
use Scalar::Util qw(weaken);
use Test::More tests => 2;

my ($weak_dbh, $sth);
{
    my $dbh = DBI->connect(
        'dbi:SQLite:dbname=:memory:', '', '',
        { RaiseError => 1, PrintError => 0 },
    );
    $dbh->do('CREATE TABLE metadata_probe (id INTEGER)');
    $sth = $dbh->column_info(undef, undef, 'metadata_probe', '%');
    $weak_dbh = $dbh;
    weaken($weak_dbh);
}

ok(!defined($weak_dbh),
    'metadata statement does not retain the Perl DBH object');
my $column = $sth->fetchrow_hashref;
is($column->{COLUMN_NAME}, 'id',
    'metadata statement remains usable after DBH scope exit');
