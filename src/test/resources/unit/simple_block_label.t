#!/usr/bin/env perl

use strict;
use warnings;

print "1..1\n";

# Test: Simple labeled block (no goto, just checking we didn't break labels)
{
    my $x = 0;
    LABEL: {
        $x = 1;
    }
    if ($x == 1) {
        print "ok 1 - labeled block works\n";
    } else {
        print "not ok 1 - labeled block failed\n";
    }
}

