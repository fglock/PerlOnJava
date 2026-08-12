use strict;
use warnings;
use Config;
use threads;

print "1..14\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $advertised = $Config{useithreads} || '';
check($advertised eq 'define',
    'thread capability flag advertises the active implementation');
check(threads->self->tid == 0, 'main thread has tid zero');

my $parent = 10;
my ($thread) = threads->create(sub {
    my ($value) = @_;
    $parent = 99;
    return ($value + 1, $parent);
}, 41);

check($thread->tid > 0, 'child has a positive tid');
check($thread->equal($thread), 'thread equality');
my @values = $thread->join;
check(@values == 2 && $values[0] == 42 && $values[1] == 99,
    'list join returns the child result');
check($parent == 10, 'captured parent value remains isolated');
check(!$thread->is_running, 'joined thread is not running');
check(!$thread->is_joinable, 'joined thread is not joinable');
check(!$thread->is_detached, 'joined thread is not detached');
check(!($thread->error || ''), 'successful thread has no error');

my $scalar = threads->create(sub { return (1, 2, 3) })->join;
check($scalar == 3, 'scalar join returns the last value');

my $detached = async { 7 };
$detached->detach;
check($detached->is_detached, 'async and detach work');
check(threads->self == threads->self, 'overloaded equality uses tid');
check("" . threads->self =~ /^threads=/, 'default thread stringification is stable');
