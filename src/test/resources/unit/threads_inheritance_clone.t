use strict;
use warnings;
use threads;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

package ThreadParent;
sub inherited { return 42 }

package ThreadChild;
our @ISA = ('ThreadParent');

package main;
check(ThreadChild->inherited == 42, 'parent resolves inherited method');
my $thread = threads->create(sub {
    return join ':', @ThreadChild::ISA, ThreadChild->inherited;
});
check($thread->join eq 'ThreadParent:42', 'child preserves ISA and method lookup');
check(!$thread->error, 'child inherited call has no thread error');
