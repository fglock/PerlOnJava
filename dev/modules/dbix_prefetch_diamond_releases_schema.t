use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

use lib $ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
use lib $ENV{DBIX_CLASS_TEST_LIB}
    // die "Set DBIX_CLASS_TEST_LIB to the DBIx::Class t/lib directory\n";
use DBICTest;

my $schema = DBICTest->init_schema();
my $weak_schema;
weaken($weak_schema = $schema);

$schema->populate('Artwork', [[qw/cd_id/], [1]]);
$schema->populate('Artwork_to_Artist', [[qw/artwork_cd_id artist_id/], [1, 2]]);

my $ars = $schema->resultset('Artwork');
my $cd_paths = {
    'no cd' => [], 'no cd empty' => [''], 'no cd undef' => [undef],
    'no cd href' => [{}], 'no cd aoh' => [[{}]],
    'no cd complex' => [[[undef]]], 'cd' => ['cd'],
    'cd->artist1' => [{ cd => 'artist' }],
};
my $a2a_paths = {
    'no a2a' => [], 'no a2a empty' => [''], 'no a2a undef' => [undef],
    'no a2a href' => [{}], 'no a2a aoh' => [[{}]],
    'no a2a complex' => [['']], 'a2a' => ['artwork_to_artist'],
    'a2a->artist2' => [{ artwork_to_artist => 'artist' }],
};

# Keep the ResultSets in file-scope storage like DBIx::Class's diamond test.
# Releasing an equivalent cache inside a helper sub does not reproduce the
# lifecycle failure.
my %tests;
for my $cd_path (keys %$cd_paths) {
    for my $a2a_path (keys %$a2a_paths) {
        $tests{"join $cd_path, $a2a_path"} = $ars->search({}, {
            join => [@{$cd_paths->{$cd_path}}, @{$a2a_paths->{$a2a_path}}],
            prefetch => [],
        });
        $tests{"prefetch $cd_path, $a2a_path"} = $ars->search({}, {
            join => [],
            prefetch => [@{$cd_paths->{$cd_path}}, @{$a2a_paths->{$a2a_path}}],
        });
        $tests{"join $cd_path, prefetch $a2a_path"} = $ars->search({}, {
            join => [@{$cd_paths->{$cd_path}}],
            prefetch => [@{$a2a_paths->{$a2a_path}}],
        });
        $tests{"join $a2a_path, prefetch $cd_path"} = $ars->search({}, {
            join => [@{$a2a_paths->{$a2a_path}}],
            prefetch => [@{$cd_paths->{$cd_path}}],
        });
    }
}

my $rows_seen = 0;
my (@weak_cds, @weak_cd_artists, @weak_associations, @weak_association_artists);
for my $name (keys %tests) {
    for my $artwork ($tests{$name}->all()) {
        die "$name returned the wrong artwork" unless $artwork->id == 1;
        my $cd = $artwork->cd;
        push @weak_cds, $cd;
        weaken($weak_cds[-1]);
        my $cd_artist = $cd->artist;
        push @weak_cd_artists, $cd_artist;
        weaken($weak_cd_artists[-1]);
        my $association = $artwork->artwork_to_artist->first;
        push @weak_associations, $association;
        weaken($weak_associations[-1]);
        my $association_artist = $association->artist;
        push @weak_association_artists, $association_artist;
        weaken($weak_association_artists[-1]);
        die "$name returned the wrong cd artist"
            unless $cd_artist->artistid == 1;
        die "$name returned the wrong association artist"
            unless $association_artist->artistid == 2;
        ++$rows_seen;
    }
}

is($rows_seen, 256, 'all diamond join/prefetch resultsets return the expected row');

my @weak_resultsets;
for my $name (keys %tests) {
    push @weak_resultsets, $tests{$name};
    weaken($weak_resultsets[-1]);
}

undef %tests;
undef $ars;
undef $schema;
is(scalar(grep { defined $_ } @weak_cds), 0,
    'CD rows are released after the diamond resultsets are discarded');
is(scalar(grep { defined $_ } @weak_cd_artists), 0,
    'CD artist rows are released after the diamond resultsets are discarded');
is(scalar(grep { defined $_ } @weak_associations), 0,
    'association rows are released after the diamond resultsets are discarded');
is(scalar(grep { defined $_ } @weak_association_artists), 0,
    'association artist rows are released after the diamond resultsets are discarded');
is(scalar(grep { defined $_ } @weak_resultsets), 0,
    'discarded diamond cache releases every resultset');
ok(!defined($weak_schema), 'discarded diamond resultset cache releases its schema');

done_testing();
