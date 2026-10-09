use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

use lib ($ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n"), 't/lib';
use DBICTest;

my $storage_weak;
{
    my $schema = DBICTest->init_schema();
    my $storage = $schema->storage;
    $storage_weak = $storage;
    weaken($storage_weak);
}

END {
    ok(!defined $storage_weak,
        'Storage is released after its schema and local reference leave scope');
    done_testing();
}
