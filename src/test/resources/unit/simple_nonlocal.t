#!/usr/bin/env perl

use strict;
use warnings;

print "1..1\n";

# Simple non-local last
my $result = 0;

sub outer {
    OUTER: for my $i (1..10) {
        print "# In outer loop, i=$i\n";
        inner();
        $result = $i;
        print "# After inner(), result=$result\n";
    }
    print "# After loop\n";
}

sub inner {
    print "# In inner()\n";
    last OUTER;
    print "# After last (should not print)\n";
}

outer();
print "# result=$result\n";

if ($result == 0) {
    print "ok 1 - non-local last OUTER\n";
} else {
    print "not ok 1 - non-local last OUTER (result=$result)\n";
}

