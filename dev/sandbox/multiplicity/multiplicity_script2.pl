# multiplicity_script2.pl — Demonstrates isolated state in interpreter 2
use strict;
use warnings;

my $id = "Interpreter-2";
$_ = "Greetings from $id";

# This variable should NOT see interpreter 1's value
our $shared_test = 99;

# Different regex — state should not leak from interpreter 1
"2025-04-10" =~ /(\d{4})-(\d{2})-(\d{2})/;
my $match = "$1/$2/$3";

# Different computation
my $product = 1;
for my $i (1..10) {
    $product *= $i;
}

print "[$id] \$_ = $_\n";
print "[$id] Regex match: $match\n";
print "[$id] \$shared_test = $shared_test\n";
print "[$id] 10! = $product\n";
print "[$id] \@INC has " . scalar(@INC) . " entries\n";
print "[$id] Done!\n";
