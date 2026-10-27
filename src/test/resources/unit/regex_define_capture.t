use strict;
use warnings;
use Test::More tests => 4;

# Regression: matcherNoRegion lost capture groups for patterns with
# (?(DEFINE)...) and (?&name) subroutine calls.  [perl #122890]

my $str = "7% solution";
ok($str =~ /( (?&solution) | % ) \Z (?(DEFINE) (?<solution>7\%\ solution) )/x,
   "recursive regex with DEFINE matches");
is($1, "7% solution", "capture group populated via (?&name) subroutine call");

# Simpler capture-group test to ensure matcherNoRegion guard works
$str = "hello world";
ok($str =~ /(hello) (world)/, "simple two-group capture matches");
is($1, "hello", "first capture group correct");
