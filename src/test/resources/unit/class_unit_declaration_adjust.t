use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';

my $adjust_runs = 0;
class UnitDeclarationAdjust;
ADJUST { $adjust_runs++ }
package main;

print "1..3\n";
if ($adjust_runs == 0) {
    print "ok 1 - ADJUST waits until the unit class is constructed\n";
} else {
    print "not ok 1 - ADJUST ran before construction\n";
}
my $instance = UnitDeclarationAdjust->new();
if ($adjust_runs == 1) {
    print "ok 2 - ADJUST after a unit class declaration runs at construction\n";
} else {
    print "not ok 2 - ADJUST did not run at construction\n";
}
if (ref($instance) eq 'UnitDeclarationAdjust') {
    print "ok 3 - constructor returns the declared unit class\n";
} else {
    print "not ok 3 - constructor returned an unexpected class\n";
}
