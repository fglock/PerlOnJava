use strict;
use warnings;
use threads;
use threads::shared;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $release :shared = 0;
my $worker = threads->create(sub {
    sleep 0.01 until $release;
});
$worker->detach;

check(!grep($_->tid == $worker->tid, threads->list()),
    'default list excludes a live detached thread');
check(!grep($_->tid == $worker->tid, threads->list(threads::all)),
    'all list excludes a live detached thread');
check(!grep($_->tid == $worker->tid, threads->list(threads::running)),
    'running list excludes a live detached thread');
$release = 1;
