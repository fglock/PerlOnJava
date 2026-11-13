use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# Optional integration regression for DBIx::Class t/52leaks.t's phantom chain.
# Set DBIC_CLASS_TEST_ROOT to an unpacked DBIx::Class tree to run it; ordinary
# unit runs skip this external fixture.
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

sub watch {
    my ($weak, $name, $value) = @_;
    $weak->{$name} = $value;
    weaken($weak->{$name});
}

my %weak;
{
    my $phantom;
    my @chain = (
        sub { DBICTest->init_schema(sqlite_use_file => 0) },
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
    );

    for my $index (0 .. $#chain) {
        $phantom = $chain[$index]->($phantom);
        if ($index == 0) {
            watch(\%weak, 'schema', $phantom);
            my $storage = $phantom->storage;
            watch(\%weak, 'storage', $storage);
            my $dbh = $storage->_dbh;
            watch(\%weak, 'dbh', $dbh);
        }
        elsif ($index == 3) {
            watch(\%weak, 'artist_source', $phantom);
        }
        elsif ($index == 11) {
            watch(\%weak, 'cd_row', $phantom);
        }
        elsif ($index == 12) {
            watch(\%weak, 'artist_row', $phantom);
        }
    }

    ok($phantom && $phantom->name, 'the phantom chain returns its related Artist');
}

for my $name (qw(artist_row cd_row artist_source schema storage dbh)) {
    my $released = !defined($weak{$name});
    diag("$name owner trace: " . Internals::jperl_owner_trace($weak{$name}))
        if !$released && defined(&Internals::jperl_owner_trace);
    ok($released, "$name is released after the phantom chain scope");
}
