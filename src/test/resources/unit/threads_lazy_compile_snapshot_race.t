use strict;
use warnings;

use Test::More tests => 8;
use threads;
use threads::shared;

# Keep a sizeable set of named subs lazy until the first child runs them.  The
# parent immediately snapshots more children at the same time; compiling a
# child CV must never mutate metadata underneath that parent snapshot.
my @helpers;
for my $number (0 .. 127) {
    my $name = "lazy_snapshot_helper_$number";
    my $definition = "sub $name { $number + shift }";
    eval $definition;
    die $@ if $@;
    no strict 'refs';
    push @helpers, \&{$name};
}

my $race_ready :shared = 0;
my $race_go :shared = 0;

sub run_lazy_helpers {
    my ($seed) = @_;
    my $sum = 0;
    $sum += $_->($seed) for @helpers;
    return $sum;
}

sub run_lazy_helpers_after_barrier {
    my ($seed) = @_;
    {
        lock($race_ready);
        $race_ready = 1;
        cond_signal($race_ready);
    }
    {
        lock($race_go);
        cond_wait($race_go) until $race_go;
    }
    return run_lazy_helpers($seed);
}

# Hold the first worker immediately before its lazy calls, then release it just
# before the parent snapshots five more children. This makes compiler mutation
# and snapshot traversal overlap reliably instead of depending on startup luck.
my @workers = (threads->create(\&run_lazy_helpers_after_barrier, 0));
{
    lock($race_ready);
    cond_wait($race_ready) until $race_ready;
}
{
    lock($race_go);
    $race_go = 1;
    cond_broadcast($race_go);
}
push @workers, map { threads->create(\&run_lazy_helpers, $_) } 1 .. 5;
my @results = map { $_->join } @workers;
my $base = 127 * 128 / 2;

is(scalar @results, 6, 'all workers join after concurrent snapshots');
is($results[$_], $base + 128 * $_, "worker $_ runs every lazy helper")
    for 0 .. 5;

# A child owns its managed-runtime execution lock until its body returns.  Keep
# this helper lazy through the child's snapshot so the grandchild has to
# materialize its child-owned source CV while the child waits in join.  Lazy
# compilation must not try to acquire that execution lock.
sub nested_lazy_helper {
    return 40 + shift;
}

sub nested_launcher {
    return nested_lazy_helper(shift);
}

sub launch_nested_thread {
    my $nested = threads->create(\&nested_launcher, 2);
    return $nested->join;
}

my $outer = threads->create(\&launch_nested_thread);
is($outer->join, 42, 'nested child materializes a lazy source CV without deadlock');
