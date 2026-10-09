use strict;
use warnings;
use Test::More tests => 2;
use DBI;
use Scalar::Util qw(weaken);

my $weak_sth;
{
    my $dbh = DBI->connect(
        'dbi:SQLite:dbname=:memory:', '', '',
        { RaiseError => 1, PrintError => 0 },
    );
    ok($dbh, 'connected to the in-memory database');

    {
        my $sth = $dbh->prepare_cached('SELECT 1');
        $weak_sth = $sth;
        weaken($weak_sth);
    }
}

ok(!defined($weak_sth),
    'cached statement is released when its database handle is destroyed');
