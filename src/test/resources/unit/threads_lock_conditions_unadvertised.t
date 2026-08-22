use strict;
use warnings;
use threads;
use threads::shared;
use Time::HiRes qw(time usleep);

print "1..14\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $recursive :shared = 3;
{
    lock($recursive);
    {
        lock($recursive);
        ++$recursive;
    }
    ++$recursive;
}
check($recursive == 5, 'lock is recursive');

my $exceptional :shared = 0;
eval {
    lock($exceptional);
    $exceptional = 1;
    die "leave locked scope\n";
};
my $after_exception = threads->create(sub {
    lock($exceptional);
    return ++$exceptional;
})->join;
check($after_exception == 2, 'exceptional scope exit releases lock');

my $condition :shared;
my $ready :shared = 0;
my $go :shared = 0;
my @waiters = map {
    threads->create(sub {
        lock($condition);
        ++$ready;
        my $deadline = time + 5;
        cond_timedwait($condition, $deadline) until $go;
        return time < $deadline - 0.1 ? 1 : 0;
    })
} 1 .. 2;

my $saw_both;
for (1 .. 500) {
    {
        lock($condition);
        if ($ready == 2) {
            $saw_both = 1;
            $go = 1;
            cond_broadcast($condition);
            last;
        }
    }
    usleep(10_000);
}
check($saw_both, 'both condition waiters registered');
my $woken = 0;
for my $waiter (@waiters) {
    my $result = $waiter->join;
    ++$woken if $result == 1;
}
check($woken == 2, 'cond_broadcast wakes all waiters');

my $signal_condition :shared;
my $signal_lock :shared;
my $signal_ready :shared = 0;
my $signal_value :shared = 0;
my $one = threads->create(sub {
    lock($signal_lock);
    $signal_ready = 1;
    cond_wait($signal_condition, $signal_lock) until $signal_value;
    return $signal_value;
});
usleep(10_000) until $signal_ready;
{
    lock($signal_condition);
    $signal_value = 7;
    cond_signal($signal_condition);
}
check($one->join == 7, 'two-variable cond_wait wakes after cond_signal');

my $timed :shared;
my ($timed_result, $elapsed, $still_locked);
{
    lock($timed);
    my $start = time;
    $timed_result = cond_timedwait($timed, $start + 0.10);
    $elapsed = time - $start;
    $still_locked = eval { cond_signal($timed); 1 };
}
check(!defined($timed_result), 'cond_timedwait returns undef on timeout');
check($elapsed >= 0.07, 'cond_timedwait honors absolute deadline');
check($still_locked, 'cond_timedwait reacquires lock after timeout');

my $counter :shared = 0;
my @workers = map {
    threads->create(sub {
        for (1 .. 100) {
            lock($counter);
            ++$counter;
        }
        return 1;
    })
} 1 .. 4;
$_->join for @workers;
check($counter == 400, 'lock serializes concurrent mutation');

my $unlocked_warning = '';
{
    local $SIG{__WARN__} = sub { $unlocked_warning .= $_[0] };
    cond_signal($condition);
}
check($unlocked_warning =~ /unlocked variable/,
    'cond_signal diagnoses an unlocked condition');

my $suppressed_warning = '';
{
    no warnings 'threads';
    local $SIG{__WARN__} = sub { $suppressed_warning .= $_[0] };
    cond_signal($condition);
}
check($suppressed_warning eq '',
    'no warnings threads suppresses an unlocked-condition warning');

my $fatal_warning = '';
{
    use warnings FATAL => 'threads';
    eval { cond_signal($condition); 1 } or $fatal_warning = $@;
}
check($fatal_warning =~ /unlocked variable/,
    'fatal threads warning throws for an unlocked condition');

my $ordinary = 0;
my $ordinary_error = eval 'lock($ordinary); 1';
check((!$ordinary_error && $@ =~ /shared/),
    'lock rejects an ordinary variable');

check(1, 'phase 21 lock and condition test completed');
