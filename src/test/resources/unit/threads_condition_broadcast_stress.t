use strict;
use warnings;
use threads;
use threads::shared;
use Time::HiRes qw(time usleep);

my $iterations = $ENV{JPERL_THREAD_BROADCAST_ITERATIONS} || 1000;
my $waiters = $ENV{JPERL_THREAD_BROADCAST_WAITERS} || 3;
my $wait_timeout = $ENV{JPERL_THREAD_BROADCAST_WAIT_TIMEOUT} || 5;

print "1..5\n";
my $test = 0;
sub check {
    my ($condition, $name) = @_;
    ++$test;
    print($condition ? 'ok ' : 'not ok ', $test, ' - ', $name, "\n");
}

my $condition :shared;
my $ready :shared = 0;
my $epoch :shared = 0;
my $abort :shared = 0;
my $timed_out :shared = 0;
my $wake_completions :shared = 0;

my @threads = map {
    threads->create(sub {
        for my $iteration (1 .. $iterations) {
            lock($condition);
            last if $abort;
            ++$ready;
            my $deadline = time + $wait_timeout;
            while (!$abort && $epoch < $iteration) {
                my $signalled = cond_timedwait($condition, $deadline);
                if (!defined $signalled) {
                    ++$timed_out;
                    last;
                }
            }
            ++$wake_completions if !$abort && $epoch >= $iteration;
        }
        return 1;
    })
} 1 .. $waiters;

my $readiness_failures = 0;
for my $iteration (1 .. $iterations) {
    my $registration_deadline = time + $wait_timeout;
    my $broadcast = 0;
    while (time < $registration_deadline) {
        {
            lock($condition);
            if ($ready == $waiters) {
                $ready = 0;
                $epoch = $iteration;
                cond_broadcast($condition);
                $broadcast = 1;
            }
        }
        last if $broadcast;
        usleep(100);
    }
    next if $broadcast;

    ++$readiness_failures;
    {
        lock($condition);
        $abort = 1;
        cond_broadcast($condition);
    }
    last;
}

my $joined = 0;
for my $thread (@threads) {
    ++$joined if $thread->join == 1;
}

check($readiness_failures == 0,
    "all $iterations epochs registered every waiter before broadcast");
check($timed_out == 0,
    'no fully-registered condition waiter timed out after broadcast');
check($wake_completions == $iterations * $waiters,
    'every broadcast released every registered waiter');
check($joined == $waiters, 'all condition waiters joined');
check(!$abort, 'broadcast stress completed without bounded abort');
