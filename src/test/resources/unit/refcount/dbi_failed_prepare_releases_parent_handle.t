use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

BEGIN {
    eval { require DBI; require DBD::SQLite; 1 }
        or plan skip_all => 'DBI and DBD::SQLite are required';
}

my $weak_dbh;
{
    my $dbh = DBI->connect(
        'dbi:SQLite::memory:', '', '',
        { RaiseError => 1, PrintError => 0 },
    );
    ok($dbh, 'connected to SQLite');

    $weak_dbh = $dbh;
    weaken($weak_dbh);

    my $prepare_error;
    eval { $dbh->prepare('_NOPE_'); 1 } or $prepare_error = $@;
    like($prepare_error, qr/prepare|syntax|near/i,
        'invalid SQL fails while preparing a statement');
    ok(defined $weak_dbh, 'the caller still owns the database handle');
}

ok(!defined $weak_dbh,
    'a failed, unreturned statement does not retain the database handle');

done_testing;
