use strict;
use warnings;
use threads;
use threads::shared;

our @roots :shared;
share(@roots);
my $ready :shared = 0;
my $start :shared = 0;

print "1..8\n";
my $test = 0;
sub check {
    my ($condition, $name) = @_;
    ++$test;
    print($condition ? "ok " : "not ok ", "$test - $name\n");
}

my @workers;
for my $worker (1 .. 8) {
    push @workers, threads->create(sub {
        {
            lock($ready);
            ++$ready;
            cond_broadcast($ready);
        }
        {
            lock($start);
            cond_wait($start) until $start;
        }

        # Worker 1 exits while the other threads keep changing the shared
        # array. Its per-thread END/reachability walk must not iterate the
        # live ArrayList and fail with ConcurrentModificationException.
        my $iterations = $worker == 1 ? 10 : 3000;
        for (1 .. $iterations) {
            lock(@roots);
            push @roots, $_;
            shift @roots if @roots > 16;
        }
        return $worker;
    });
}

{
    lock($ready);
    cond_wait($ready) until $ready == 8;
}
{
    lock($start);
    $start = 1;
    cond_broadcast($start);
}

for my $worker (1 .. 8) {
    my $result = eval { $workers[$worker - 1]->join() };
    check(!$@ && defined($result) && $result == $worker,
        "worker $worker exits cleanly during shared-array mutation");
}
