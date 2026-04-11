# multiplicity_script1.pl — Demonstrates isolated state in interpreter 1
use strict;
use warnings;

my $id = "Interpreter-1";
$_ = "Hello from $id";

# Set a global variable
our $shared_test = 42;

# Regex match — regex state ($1, $&, etc.) should be isolated
"The quick brown fox" =~ /(\w+)\s+(\w+)/;
my $match = "$1 $2";

# Simulate some work
my $sum = 0;
for my $i (1..1000) {
    $sum += $i;
}

print "[$id] \$_ = $_\n";
print "[$id] Regex match: $match\n";
print "[$id] \$shared_test = $shared_test\n";
print "[$id] Sum 1..1000 = $sum\n";
print "[$id] \@INC has " . scalar(@INC) . " entries\n";
print "[$id] Done!\n";
