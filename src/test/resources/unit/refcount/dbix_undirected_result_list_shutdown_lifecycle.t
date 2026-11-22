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

plan tests => 6;

our ($weak_schema, $weak_storage, $weak_dbh, $weak_producer);
{
    my $schema = DBICTest->init_schema();
    my $storage = $schema->storage;
    my $dbh = $storage->_dbh;
    ($weak_schema, $weak_storage, $weak_dbh) = ($schema, $storage, $dbh);
    weaken($weak_schema);
    weaken($weak_storage);
    weaken($weak_dbh);

    my $cd = $schema->resultset('CD')->find(2);
    my $producer = $schema->resultset('Producer')->find(1);
    $weak_producer = $producer;
    weaken($weak_producer);
    $cd->set_producers([$producer], { attribute => 2 });
    undef $producer;
    ok(!defined($weak_producer), 'set_producers releases its temporary array argument');

    my $maps = $schema->resultset('Artist')->search({ artistid => 1 })
        ->search_related('artist_undirected_maps');
    is($maps->count, 1, 'undirected map query returns one row');
    my $mapped = $schema->resultset('Artist')->find(2)->artist_undirected_maps;
    my @artists = $mapped->search_related('mapped_artists')->all;
    is(scalar @artists, 2, 'undirected relationship returns both artist rows');
}

END {
    ok(!defined($weak_schema), 'schema is released at file shutdown');
    ok(!defined($weak_storage), 'storage is released at file shutdown');
    ok(!defined($weak_dbh), 'DBI handle is released at file shutdown');
}
