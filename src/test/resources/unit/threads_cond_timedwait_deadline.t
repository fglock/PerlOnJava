use strict;
use warnings;
use threads;
use threads::shared;
use Time::HiRes qw(time);

print "1..9\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $condition :shared;
for my $round (1 .. 3) {
    my ($result, $elapsed, $relocked);
    {
        lock($condition);
        my $start = time;
        $result = cond_timedwait($condition, $start + 0.125);
        $elapsed = time - $start;
        $relocked = eval { cond_signal($condition); 1 };
    }
    check(!defined($result), "round $round returns undef at its absolute deadline");
    check($elapsed >= 0.09, "round $round does not return before its absolute deadline");
    check($relocked, "round $round reacquires the shared lock after timeout");
}
