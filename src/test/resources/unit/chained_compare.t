###################
# Chained Operators

use 5.38.0;

say "1..13";

# Chained numeric comparisons
my $result = 3 < 6 < 5;
print "not " if $result;
say "ok # 3 < 6 < 5 is false";

$result = 1 < 2 < 3;
print "not " if !$result;
say "ok # 1 < 2 < 3 is true";

$result = 5 > 3 > 1;
print "not " if !$result;
say "ok # 5 > 3 > 1 is true";

$result = 5 > 3 > 4;
print "not " if $result;
say "ok # 5 > 3 > 4 is false";

# Mixed comparison operators
$result = 1 <= 2 < 3;
print "not " if !$result;
say "ok # 1 <= 2 < 3 is true";

$result = 3 >= 3 > 2;
print "not " if !$result;
say "ok # 3 >= 3 > 2 is true";

# Chained equality operators
$result = 5 == 5 == 1;
print "not " if $result;
say "ok # 5 == 5 == 1 is true";

$result = 5 != 6 != 0;
print "not " if !$result;
say "ok # 5 != 6 != 0 is true";

# String comparisons
$result = "a" lt "b" lt "c";
print "not " if !$result;
say "ok # 'a' lt 'b' lt 'c' is true";

$result = "cat" gt "bat" gt "ant";
print "not " if !$result;
say "ok # 'cat' gt 'bat' gt 'ant' is true";

# Mixed numeric and string comparisons (should not chain)
$result = 5 < 6 eq "1";
print "not " if !$result;
say "ok # 5 < 6 eq '1' is true";

# Long chains
$result = 1 < 2 < 3 < 4 < 5;
print "not " if !$result;
say "ok # 1 < 2 < 3 < 4 < 5 is true";

$result = 5 > 4 > 3 > 2 > 1;
print "not " if !$result;
say "ok # 5 > 4 > 3 > 2 > 1 is true";

