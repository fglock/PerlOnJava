use strict;
use warnings;

use FindBin;
use Test::More;
use lib "$FindBin::Bin/../lib";
use PerlTestRunner::Scheduler qw(
    effective_weight
    next_runnable_index
    profile_for_test
    scheduling_priority
    duration_priority
    test_can_start
);

sub profile_is {
    my ($path, $class, $weight, $name) = @_;
    my $profile = profile_for_test($path);
    is_deeply(
        $profile,
        {
            class => $class,
            weight => $weight,
        },
        $name,
    );
}

profile_is('src/test/resources/unit/array.t', 'normal', 1,
    'ordinary semantic test consumes one unit');
profile_is('perl5_t/t/re/pat_psycho.t', 'heavy', 3,
    'regex stress test consumes three units');
profile_is('perl5_t/t/re/pat_psycho_thr.t', 'heavy', 3,
    'threaded regex stress wrapper consumes three units');
profile_is('/checkout/perl5_t/t/op/gv.t', 'heavy', 3,
    'absolute heavy-test path is recognized');
profile_is('C:\\checkout\\perl5_t\\t\\re\\pat_psycho.t', 'heavy', 3,
    'Windows heavy-test path is recognized');
profile_is('perl5/dist/threads/t/join.t', 'heavy', 3,
    'load-sensitive synchronization test uses weighted capacity');
profile_is('perl5_t/t/benchmark/gh7094-speed-up-keys-on-empty-hash.t',
    'normal', 1, 'timing benchmark has no semantic scheduling privilege');

my $heavy = profile_for_test('perl5_t/t/re/pat_psycho.t');
my $normal = profile_for_test('unit/example.t');
is(scheduling_priority($heavy), 1,
    'known long-running work is scheduled before ordinary tests');
is(scheduling_priority($normal), 2,
    'uniform ordinary work fills the remaining budget');

is(duration_priority('perl5_t/t/re/anyof.t'), 0,
    'the longest direct regex fixture is admitted first');
is(duration_priority('C:\\checkout\\perl5_t\\t\\re\\anyof_thr.t'), 0,
    'the longest threaded regex fixture is admitted first');
ok(duration_priority('perl5_t/t/re/pat_thr.t')
    < duration_priority('perl5_t/t/re/pat.t'),
    'the threaded full regex corpus precedes the direct corpus');
ok(duration_priority('perl5_t/t/re/pat.t')
    < duration_priority('perl5_t/t/re/pat_psycho.t'),
    'the full regex corpus precedes shorter stress fixtures');
is(duration_priority('unit/example.t'), 100,
    'unknown tests retain the final duration tier');

is(effective_weight($heavy, 2), 2,
    'heavy weight is clamped to a small caller budget');
is(effective_weight($heavy, 10), 3,
    'heavy weight is retained within a larger caller budget');

ok(test_can_start($heavy, 10, 6, 2, 0),
    'third heavy test fits a ten-unit budget');
ok(!test_can_start($heavy, 10, 9, 3, 0),
    'fourth heavy test exceeds a ten-unit budget');
ok(test_can_start($normal, 10, 9, 3, 0),
    'normal test can use the final scheduling unit');
my @weighted_queue = map { +{ profile => $_ } }
    ($heavy, $heavy, $normal);
is(next_runnable_index(\@weighted_queue, 10, 9, 3, 0), 2,
    'light work may bypass a heavy test that does not fit remaining capacity');

my @initial_queue = map { +{ profile => $_ } }
    ($heavy, $heavy, $heavy, $heavy, $normal, $normal);
my ($initial_weight, @initial_classes) = (0);
while (1) {
    my $index = next_runnable_index(
        \@initial_queue,
        10,
        $initial_weight,
        scalar(@initial_classes),
        0,
    );
    last unless defined $index;
    my ($test) = splice @initial_queue, $index, 1;
    $initial_weight += effective_weight($test->{profile}, 10);
    push @initial_classes, $test->{profile}{class};
}
is_deeply(\@initial_classes, [qw(heavy heavy heavy normal)],
    'longest-first admission fills spare capacity with ordinary work');
is($initial_weight, 10, 'initial admission never exceeds caller budget');

my @longest_first = map { +{ test_file => $_, profile => profile_for_test($_) } }
    qw(perl5_t/t/re/pat.t perl5_t/t/re/anyof.t src/test/resources/unit/array.t);
@longest_first = sort {
       scheduling_priority($a->{profile}) <=> scheduling_priority($b->{profile})
    || duration_priority($a->{test_file}) <=> duration_priority($b->{test_file})
} @longest_first;
is_deeply(
    [map { $_->{test_file} } @longest_first],
    [qw(perl5_t/t/re/anyof.t perl5_t/t/re/pat.t src/test/resources/unit/array.t)],
    'known longest tests are ordered before quicker tests');

done_testing;
