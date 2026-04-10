# multiplicity_script3.pl — Demonstrates isolated state in interpreter 3
use strict;
use warnings;

my $id = "Interpreter-3";
$_ = "Bonjour from $id";

# Independent global
our $shared_test = -1;

# Yet another regex
"foo\@bar.com" =~ /(\w+)\@(\w+)\.(\w+)/;
my $match = "user=$1 domain=$2 tld=$3";

# Fibonacci
my @fib = (0, 1);
for my $i (2..20) {
    push @fib, $fib[-1] + $fib[-2];
}

print "[$id] \$_ = $_\n";
print "[$id] Regex match: $match\n";
print "[$id] \$shared_test = $shared_test\n";
print "[$id] Fib(20) = $fib[20]\n";
print "[$id] \@INC has " . scalar(@INC) . " entries\n";
print "[$id] Done!\n";
