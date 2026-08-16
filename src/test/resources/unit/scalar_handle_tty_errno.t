use strict;
use warnings;

print "1..2\n";

my $buffer = "text";
open my $handle, '<', \$buffer or die $!;
$! = 0;
my $is_tty = -t $handle;
print !defined($is_tty) ? "ok 1\n" : "not ok 1\n";
print 0 + $! == 9 ? "ok 2\n" : "not ok 2\n";
