use strict;
use warnings;
use threads;
use threads::shared;

my $base = 0;
sub report {
    my ($offset, $actual, $expected, $name) = @_;
    print($actual eq $expected ? "ok " : "not ok ", $base + $offset, " - $name\n");
}

print "1..10\n";

my $lock : shared;
my $thread;

{
    lock($lock);
    report(1, 1, 1, 'parent acquires direct lock');
    $thread = async {
        lock($lock);
        report(3, 1, 1, 'child acquires direct lock');
    };
    threads->yield;
    report(2, 1, 1, 'parent retains direct lock');
}
$thread->join;
report(4, threads::shared::_id($lock) > 0, 1, 'shared scalar survives a direct child lock');

{
    my $lockref = \$lock;
    lock($lockref);
    report(5, 1, 1, 'parent acquires reference lock');
    $thread = async {
        lock($lockref);
        report(7, 1, 1, 'child acquires reference lock');
    };
    threads->yield;
    report(6, 1, 1, 'parent retains reference lock');
}
$thread->join;
report(8, threads::shared::_id($lock) > 0, 1, 'shared scalar survives a reference child lock');

{
    lock($lock);
    lock($lock);
    report(9, 1, 1, 'parent acquires recursive locks');
    {
        lock($lock);
    }
    $thread = async { lock($lock); };
    {
        lock($lock);
        lock($lock);
    }
}
$thread->join;
report(10, threads::shared::_id($lock) > 0, 1, 'recursive locking preserves shared identity');
