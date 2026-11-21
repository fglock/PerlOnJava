use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

our ($issue2043_weak_schema, $issue2043_weak_rs);

use lib $ENV{DBIX_CLASS_LIB}
    // die "Set DBIX_CLASS_LIB to the installed DBIx::Class lib directory\n";
use lib $ENV{DBIX_CLASS_TEST_LIB}
    // die "Set DBIX_CLASS_TEST_LIB to the DBIx::Class t/lib directory\n";
use DBICTest;

my $schema = DBICTest->init_schema();
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

# Retain the 256 resultsets at file scope like DBIx::Class's prefetch/diamond.t.
# Its LeakTracer END block must observe their release without manual undef.
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
# A single prefetch query is enough to expose the shutdown leak, provided the
# other 255 resultsets remain cached at file scope. Traversing every query
# makes the regression take several minutes without improving coverage.
my $name = 'join a2a, prefetch cd';
$issue2043_weak_schema = $schema;
$issue2043_weak_rs = $tests{$name};
weaken($issue2043_weak_schema);
weaken($issue2043_weak_rs);
for my $artwork ($tests{$name}->all()) {
    die "$name returned the wrong artwork" unless $artwork->id == 1;
    die "$name returned the wrong CD artist"
        unless $artwork->cd->artist->artistid == 1;
    die "$name returned the wrong association artist"
        unless $artwork->artwork_to_artist->first->artist->artistid == 2;
    ++$rows_seen;
}

is($rows_seen, 1, 'the selected diamond join/prefetch resultset returns its row');
done_testing();

END {
    warn "[ISSUE2043-END-DIAG] schema="
        . (defined($issue2043_weak_schema) ? 'live' : 'released')
        . " selected_rs="
        . (defined($issue2043_weak_rs) ? 'live' : 'released') . "\n";
    warn "[ISSUE2043-RS-OWNERS] "
        . Internals::jperl_owner_trace($issue2043_weak_rs) . "\n"
        if defined $issue2043_weak_rs;
    warn "[ISSUE2043-SCHEMA-OWNERS] "
        . Internals::jperl_owner_trace($issue2043_weak_schema) . "\n"
        if defined $issue2043_weak_schema;
}
