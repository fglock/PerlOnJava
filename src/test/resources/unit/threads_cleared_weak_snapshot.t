use strict;
use warnings;
use threads;
use Scalar::Util qw(weaken);

print "1..3\n";

my $target = {};
my $weak = $target;
weaken($weak);
undef $target;

my $thread = threads->create(sub {
    no warnings 'uninitialized';
    print(!defined($weak) ? "ok 1" : "not ok 1",
        " - cleared weak reference clones as undef\n");
    print("$weak" eq "" ? "ok 2" : "not ok 2",
        " - cloned undef stringifies safely\n");
    print(0 + $weak == 0 ? "ok 3" : "not ok 3",
        " - cloned undef numifies safely\n");
});
$thread->join;
