use strict;
use warnings;

use FindBin;
use Test::More;
use lib "$FindBin::Bin/../lib";
use PerlTestRunner::Scheduler qw(
    effective_weight
    next_runnable_index
    profile_for_test
);

my $pat = profile_for_test('perl5_t/t/re/pat.t');
my $pat_thr = profile_for_test('perl5_t/t/re/pat_thr.t');
my $anyof = profile_for_test('perl5_t/t/re/anyof.t');

is($pat->{weight}, 5, 'direct pat consumes half of a ten-unit budget');
is($anyof->{weight}, 5, 'direct anyof consumes half of a ten-unit budget');
is($pat_thr->{weight}, 10,
    'threaded pat consumes the complete ten-unit budget');

my @queue = map { +{ profile => $_ } } ($pat, $pat_thr, $anyof);
my $active_weight = 0;
my @started;
while (1) {
    my $index = next_runnable_index(
        \@queue,
        10,
        $active_weight,
        scalar(@started),
        0,
    );
    last unless defined $index;
    my ($test) = splice @queue, $index, 1;
    $active_weight += effective_weight($test->{profile}, 10);
    push @started, $test->{profile}{weight};
}

is_deeply(\@started, [5, 5],
    'direct pat and anyof share the initial budget');
is($active_weight, 10, 'initial pat resource admission fills the budget');
is(scalar(@queue), 1, 'threaded pat waits for the direct gates');
is($queue[0]{profile}{weight}, 10,
    'threaded pat is the sole deferred resource gate');

done_testing;
