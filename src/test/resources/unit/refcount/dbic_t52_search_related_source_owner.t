use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# DBIx::Class t/52leaks.t exercises this ownership handoff in its phantom chain.
# Set DBIC_CLASS_TEST_ROOT to an unpacked DBIx::Class tree to run this optional
# integration regression; ordinary unit runs skip the external fixture.
BEGIN {
    my $root = $ENV{DBIC_CLASS_TEST_ROOT};
    plan skip_all => 'set DBIC_CLASS_TEST_ROOT to a DBIx::Class checkout'
        unless defined($root) && -d "$root/t/lib" && -d "$root/lib";

    $ENV{DBICTEST_NO_MAKEFILE_VERIFICATION} ||= 1;
    $ENV{AUTOMATED_TESTING} ||= 1;
    unshift @INC, "$root/t/lib", "$root/lib";
    require DBICTest::RunMode;
}

require DBICTest;
require Errno;
require DBI;
require DBD::SQLite;
require FileHandle;
require Moo;

my @weak_objects;
sub track_weak {
    my ($object) = @_;
    return $object unless ref $object;
    push @weak_objects, $object;
    weaken($weak_objects[-1]);
    return $object;
}

{
    my $phantom = track_weak(DBICTest->init_schema(sqlite_use_file => 0));
    track_weak($phantom->storage);
    track_weak($phantom->source($_)) for $phantom->sources;

    my @chain = (
        sub { shift->source('Artist') },
        sub { shift->resultset },
        sub { shift->result_source },
        sub { shift->schema },
        sub { shift->resultset('Artist') },
        sub { shift->find_or_create({ name => 'detachable' }) },
        sub { shift->result_source },
        sub { shift->schema },
        sub { shift->clone },
        sub { shift->resultset('CD') },
        sub { shift->next },
        sub { shift->artist },
        sub { shift->search_related('cds') },
        sub { shift->next },
        sub { shift->search_related('artist') },
        sub { shift->result_source },
        sub { shift->resultset },
        sub { shift->create({ name => 'detached' }) },
        sub { shift->update({ name => 'reattached' }) },
        sub { shift->discard_changes },
        sub { shift->delete },
        sub { shift->insert },
    );
    for my $step (@chain) {
        $phantom = track_weak(scalar $step->($phantom));
    }

    ok($phantom->in_storage, 'phantom chain returned to storage');
    is($phantom->name, 'reattached', 'phantom chain kept the updated name');
}

is(scalar(grep { defined } @weak_objects), 0,
    'phantom results, schema sources, and storage are released');

done_testing();
