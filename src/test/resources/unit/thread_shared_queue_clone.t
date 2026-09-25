use strict;
use warnings;
use Test::More tests => 2;
use threads;
use Thread::Queue;

my $queue = Thread::Queue->new();
sub enqueue_through_child {
    my ($shared_queue, $value) = @_;
    $shared_queue->enqueue($value);
    my $child = threads->create(sub {
        my ($queue, $child_value) = @_;
        $queue->enqueue($child_value);
        return $child_value;
    }, $shared_queue, $value + 12);
    return $child->join();
}

my @threads = map {
    threads->create(\&enqueue_through_child, $queue, $_);
} 1 .. 12;

is_deeply([sort { $a <=> $b } map { $_->join() } @threads], [13 .. 24],
    'nested thread joins preserve child results');
is_deeply([sort { $a <=> $b } map { $queue->dequeue_nb() } 1 .. 24], [1 .. 24],
    'shared queue remains usable during nested thread teardown');
