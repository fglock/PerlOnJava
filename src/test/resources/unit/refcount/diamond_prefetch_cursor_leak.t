use strict;
use warnings;
use Scalar::Util qw(weaken);

BEGIN {
    my $root = $ENV{DBIC_CLASS_TEST_ROOT};
    if (!defined($root) || !-d "$root/t/lib" || !-d "$root/lib") {
        print "1..0 # SKIP set DBIC_CLASS_TEST_ROOT to a DBIx::Class checkout\n";
        exit 0;
    }
    $ENV{DBICTEST_NO_MAKEFILE_VERIFICATION} ||= 1;
    $ENV{AUTOMATED_TESTING} ||= 1;
    unshift @INC, "$root/t/lib", "$root/lib";
    require DBICTest;
}

use Test::More tests => 4;

# Regression for issue #2043: the diamond-prefetch search pattern
# creates enough blessed ResultSet and Cursor objects through DBIx
# method dispatch (try/catch closures, condition resolution, attribute
# merging) that PerlOnJava's owner tracking loses an ownership edge.
# A BaseResultSet survives at refcount zero with a durable untracked
# scalar owner, keeping the Schema, Storage, and DBH alive.
#
# The leak requires volume (64+ searches) and relationship traversal
# on each returned row.  System Perl collects everything correctly.
#
# diamond.t reference: 768/768 functional assertions pass on all
# backends; JVM and interpreter each fail four shutdown leak assertions.

my %weak;
{
    my $schema = DBICTest->init_schema();
    $weak{schema}  = $schema;            weaken($weak{schema});
    $weak{storage} = $schema->storage;   weaken($weak{storage});
    $weak{dbh}     = $schema->storage->_dbh; weaken($weak{dbh});

    $schema->populate('Artwork', [[qw/cd_id/], [1]]);
    $schema->populate('Artwork_to_Artist', [[qw/artwork_cd_id artist_id/], [1, 2]]);

    my $ars = $schema->resultset('Artwork');

    my @cd_paths = (
        [],  [''],  [undef],  [{}],  [[{}]],  [[[undef]]],
        ['cd'],  {'cd' => 'artist'},
    );
    my @a2a_paths = (
        [],  [''],  [undef],  [{}],  [[{}]],  [['']],
        ['artwork_to_artist'],  {'artwork_to_artist' => 'artist'},
    );

    my %tests;
    my $idx = 0;
    for my $cd (@cd_paths) {
        for my $a2a (@a2a_paths) {
            my @join;
            push @join, ref $cd eq 'HASH' ? $cd : @$cd;
            push @join, ref $a2a eq 'HASH' ? $a2a : @$a2a;
            $tests{$idx++} = $ars->search({}, {
                join     => [@join],
                prefetch => [],
            });
        }
    }

    for my $name (keys %tests) {
        for my $artwork ($tests{$name}->all) {
            $artwork->cd->artist->artistid;
            $artwork->artwork_to_artist->first->artist->artistid;
        }
    }
}

ok(!defined $weak{schema},  'Schema collected after scope exit');
ok(!defined $weak{storage}, 'Storage collected after scope exit');
ok(!defined $weak{dbh},     'DBH collected after scope exit');

my $any_leak = defined($weak{schema})
            || defined($weak{storage})
            || defined($weak{dbh});
ok(!$any_leak, 'no diamond-prefetch cursor leak');
