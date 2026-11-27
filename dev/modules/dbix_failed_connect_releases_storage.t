use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

use lib $ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
use DBIx::Class::Schema;

my ($weak_dbh, $weak_storage);
{
    my $schema = DBIx::Class::Schema->connect(
        'dbi:SQLite:dbname=:memory:', '', '',
        {
            RaiseError => 1,
            PrintError => 0,
            on_connect_do => '_NOPE_',
        },
    );
    my $storage = $schema->storage;
    weaken($weak_storage = $storage);

    my $error;
    eval { $storage->ensure_connected; 1 } or $error = $@;
    like($error, qr/_NOPE_|syntax|near/i,
        'failed on-connect SQL is reported to the caller');
    ok(!defined($storage->_dbh),
        'failed on-connect action clears the storage database handle');
}

ok(!defined($weak_dbh), 'failed connect does not retain an unreturned handle');
ok(!defined($weak_storage),
    'failed connect releases DBIx Storage after its schema leaves scope');

done_testing();
