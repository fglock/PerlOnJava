use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

BEGIN {
    my $root = $ENV{DBIC_CLASS_TEST_ROOT};
    if (!defined($root) || !-d "$root/t/lib" || !-d "$root/lib") {
        plan skip_all => 'set DBIC_CLASS_TEST_ROOT to a DBIx::Class checkout';
    }
    unshift @INC, "$root/t/lib", "$root/lib";
    require DBICTest;
}

plan tests => 4;

our ($weak_schema, $weak_storage, $weak_dbh);
my $schema = DBICTest->init_schema();
my $storage = $schema->storage;
my $dbh = $storage->_dbh;
($weak_schema, $weak_storage, $weak_dbh) = ($schema, $storage, $dbh);
weaken($weak_schema);
weaken($weak_storage);
weaken($weak_dbh);

ok($dbh, 'DBIx schema initialized a live DBI handle');
exit 0;

END {
    ok(!defined($weak_schema), 'schema is released at file shutdown');
    ok(!defined($weak_storage), 'storage is released at file shutdown');
    ok(!defined($weak_dbh), 'DBI handle is released at file shutdown');
}
