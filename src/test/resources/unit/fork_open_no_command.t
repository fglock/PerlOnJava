use strict;
use warnings;
use Test::More tests => 3;

my $pid = open my $child, '-|';
if (!defined $pid) {
    fail('no-command fork-open starts a child');
    fail('parent can read child output');
    fail('child exits successfully');
}
elsif (!$pid) {
    print "fork-open child output\n";
    exit 0;
}
else {
    ok($pid > 0, 'no-command fork-open starts a child');
    is(<$child>, "fork-open child output\n", 'parent can read child output');
    ok(close($child), 'child exits successfully');
}
