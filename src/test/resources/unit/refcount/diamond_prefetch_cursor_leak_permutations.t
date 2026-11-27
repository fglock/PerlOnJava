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

use Test::More;

# Exercise the full search matrix from DBIx::Class's prefetch/diamond.t.
# Each of the 64 join/prefetch attribute-shape pairs creates four resultsets:
# join both sides, prefetch both sides, and the two mixed directions. Traversing
# both relationship paths for every row reproduces the method-dispatch and
# cursor lifetime pressure that the smaller 64-resultset regression misses.
my %weak;
{
    my $schema = DBICTest->init_schema();
    $weak{schema} = $schema;
    weaken($weak{schema});
    $weak{storage} = $schema->storage;
    weaken($weak{storage});
    $weak{dbh} = $schema->storage->_dbh;
    weaken($weak{dbh});

    $schema->populate('Artwork', [[qw/cd_id/], [1]]);
    $schema->populate('Artwork_to_Artist', [[qw/artwork_cd_id artist_id/], [1, 2]]);
    my $ars = $schema->resultset('Artwork');

    my $cd_paths = {
        'no cd' => [],
        'no cd empty' => [''],
        'no cd undef' => [undef],
        'no cd href' => [{}],
        'no cd aoh' => [[{}]],
        'no cd complex' => [[[undef]]],
        'cd' => ['cd'],
        'cd->artist1' => [{'cd' => 'artist'}],
    };
    my $a2a_paths = {
        'no a2a' => [],
        'no a2a empty' => [''],
        'no a2a undef' => [undef],
        'no a2a href' => [{}],
        'no a2a aoh' => [[{}]],
        'no a2a complex' => [['']],
        'a2a' => ['artwork_to_artist'],
        'a2a->artist2' => [{'artwork_to_artist' => 'artist'}],
    };

    my %tests;
    for my $cd_name (keys %$cd_paths) {
        for my $a2a_name (keys %$a2a_paths) {
            my @paths = (@{$cd_paths->{$cd_name}}, @{$a2a_paths->{$a2a_name}});

            $tests{"join $cd_name, $a2a_name"} = $ars->search({}, {
                join => [@paths],
                prefetch => [],
            });
            $tests{"prefetch $cd_name, $a2a_name"} = $ars->search({}, {
                join => [],
                prefetch => [@paths],
            });
            $tests{"join $cd_name, prefetch $a2a_name"} = $ars->search({}, {
                join => [@{$cd_paths->{$cd_name}}],
                prefetch => [@{$a2a_paths->{$a2a_name}}],
            });
            $tests{"join $a2a_name, prefetch $cd_name"} = $ars->search({}, {
                join => [@{$a2a_paths->{$a2a_name}}],
                prefetch => [@{$cd_paths->{$cd_name}}],
            });
        }
    }

    for my $name (keys %tests) {
        for my $artwork ($tests{$name}->all()) {
            is($artwork->id, 1, "$name, correct artwork");
            is($artwork->cd->artist->artistid, 1,
                "$name, correct artist_id over cd");
            is($artwork->artwork_to_artist->first->artist->artistid, 2,
                "$name, correct artist_id over A2A");
        }
    }
}

ok(!defined $weak{schema}, 'Schema collected after scope exit');
ok(!defined $weak{storage}, 'Storage collected after scope exit');
ok(!defined $weak{dbh}, 'DBH collected after scope exit');
ok(!defined($weak{schema}) && !defined($weak{storage}) && !defined($weak{dbh}),
    'no diamond-prefetch cursor leak');

done_testing();
