use strict;
use warnings;

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

# Keep this test's executable structure aligned with DBIx::Class's
# t/prefetch/diamond.t. In particular, the search resultsets live in the
# file-scope lexical pad and DBICTest's END leak checker observes them after
# top-level interpreter cleanup. Wrapping this in a block or subroutine masks
# the interpreter-only shutdown leak from issue #2043.
my $schema = DBICTest->init_schema();

$schema->populate('Artwork', [
    [qw/cd_id/],
    [1],
]);

$schema->populate('Artwork_to_Artist', [
    [qw/artwork_cd_id artist_id/],
    [1, 2],
]);

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
        $tests{"join $cd_name, $a2a_name"} = $ars->search({}, {
            join => [@{$cd_paths->{$cd_name}}, @{$a2a_paths->{$a2a_name}}],
            prefetch => [],
        });
        $tests{"prefetch $cd_name, $a2a_name"} = $ars->search({}, {
            join => [],
            prefetch => [@{$cd_paths->{$cd_name}}, @{$a2a_paths->{$a2a_name}}],
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

done_testing();
