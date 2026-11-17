use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

use lib $ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
use lib $ENV{DBIX_CLASS_TEST_LIB}
    // die "Set DBIX_CLASS_TEST_LIB to the DBIx::Class t/lib directory\n";
use DBICTest;

my $weak_dbh;
{
    my $schema = DBICTest->init_schema(
        no_deploy => 1,
        on_connect_call => sub {
            my $storage = shift;
            weaken($weak_dbh = $storage->_dbh);
            $storage->_dbh->do('_NOPE_');
        },
    );
    my $storage = $schema->storage;

    my $error;
    eval { $storage->ensure_connected; 1 } or $error = $@;
    like($error, qr/_NOPE_|syntax|near/i,
        'failed on-connect SQL is reported to the caller');
    ok(!defined($storage->_dbh),
        'failed on-connect action clears the storage database handle');
}

ok(!defined($weak_dbh),
    'failed, unreturned DBI handle is released after its schema leaves scope');

done_testing();
