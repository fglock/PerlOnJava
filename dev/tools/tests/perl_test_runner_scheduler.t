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
    test_can_start
);

sub profile_is {
    my ($path, $class, $weight, $exclusive, $name) = @_;
    my $profile = profile_for_test($path);
    is_deeply(
        $profile,
        {
            class => $class,
            weight => $weight,
            exclusive => $exclusive,
        },
        $name,
    );
}

profile_is('src/test/resources/unit/array.t', 'normal', 1, 0,
    'ordinary semantic test consumes one unit');
profile_is('perl5_t/t/re/pat_psycho.t', 'heavy', 3, 0,
    'regex stress test consumes three units');
profile_is('perl5_t/t/re/pat_psycho_thr.t', 'heavy', 3, 0,
    'threaded regex stress wrapper consumes three units');
profile_is('/checkout/perl5_t/t/op/gv.t', 'heavy', 3, 0,
    'absolute heavy-test path is recognized');
profile_is('C:\\checkout\\perl5_t\\t\\re\\pat_psycho.t', 'heavy', 3, 0,
    'Windows heavy-test path is recognized');
profile_is('perl5/dist/threads/t/join.t', 'heavy', 3, 0,
    'load-sensitive synchronization test uses weighted capacity');
profile_is('perl5_t/t/benchmark/gh7094-speed-up-keys-on-empty-hash.t',
    'normal', 1, 0, 'timing benchmark has no semantic scheduling privilege');

my $heavy = profile_for_test('perl5_t/t/re/pat_psycho.t');
my $normal = profile_for_test('unit/example.t');
my $exclusive = {
    class => 'exclusive',
    weight => 1,
    exclusive => 1,
};

is(scheduling_priority($exclusive), 0,
    'future exclusive semantic work receives the earliest scheduling class');
is(scheduling_priority($heavy), 1,
    'known long-running work is scheduled before ordinary tests');
is(scheduling_priority($normal), 2,
    'uniform ordinary work fills the remaining budget');

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
ok(!test_can_start($exclusive, 10, 1, 1, 0),
    'exclusive test waits for active work');
ok(test_can_start($exclusive, 10, 0, 0, 0),
    'exclusive test starts on an idle runner');
ok(!test_can_start($normal, 10, 1, 1, 1),
    'ordinary work waits while an exclusive test is active');

my @weighted_queue = map { +{ profile => $_ } }
    ($heavy, $heavy, $normal);
is(next_runnable_index(\@weighted_queue, 10, 9, 3, 0), 2,
    'light work may bypass a heavy test that does not fit remaining capacity');

my @barrier_queue = map { +{ profile => $_ } }
    ($exclusive, $normal);
ok(!defined next_runnable_index(\@barrier_queue, 10, 3, 1, 0),
    'work never bypasses a waiting exclusive barrier');
is(next_runnable_index(\@barrier_queue, 10, 0, 0, 0), 0,
    'exclusive barrier is selected once the runner is idle');

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

done_testing;
