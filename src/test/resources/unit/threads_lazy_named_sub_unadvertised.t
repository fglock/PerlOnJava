use strict;
use warnings;
use threads;

print "1..1\n";

sub child_named_sub {
    return 42;
}

my $thread = threads->create(sub { child_named_sub() });
my $value = $thread->join;
print $value == 42
    ? "ok 1 - child can call a lazily compiled named sub\n"
    : "not ok 1 - child can call a lazily compiled named sub\n";
