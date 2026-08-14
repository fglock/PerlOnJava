use strict;
use warnings;
use threads;

print "1..3\n";

my $thread;
eval q{$thread = threads->create(sub { qr/[ / })};
print($@ =~ /Unmatched \[/ ? "ok 1" : "not ok 1",
    " - malformed regex in entry CODE fails in the parent\n");
print(!defined($thread) ? "ok 2" : "not ok 2",
    " - failed entry compilation does not create a thread\n");

my $valid = threads->create(sub { qr/[a]/; return 42 });
print($valid->join == 42 ? "ok 3" : "not ok 3",
    " - a valid compiled entry still runs in the child\n");
