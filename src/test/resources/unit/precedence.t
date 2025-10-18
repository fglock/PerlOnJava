use strict;
use feature 'say';

print "1..15\n";

# Test precedence of ++ vs ->
my ($flush_mro, $pkg);
$flush_mro = {a => 2};
$pkg = "a";
++$flush_mro->{$pkg};
print "not " if $flush_mro->{a} != 3; say "ok # ++ vs ->";

# Test precedence of ** vs unary -
my $result = -2**2;
print "not " if $result != -4; say "ok # ** vs unary -";

# Test precedence of * vs +
$result = 2 + 3 * 4;
print "not " if $result != 14; say "ok # * vs +";

# Test precedence of && vs ||
$result = 0 || 1 && 0;
print "not " if $result != 0; say "ok # && vs ||";

# Test precedence of ?: vs =
my $value;
$value = 1 ? 2 : 3;
print "not " if $value != 2; say "ok # ?: vs =";

# Test precedence of , vs =>
my @array = (1, 2, 3);
my %hash = (a => 1, b => 2, c => 3);
my $comma_result = (1, 2, 3);
print "not " if $comma_result != 3; say "ok # , vs =>";

# Test precedence of and vs or
$result = 0 or 1 and 0;
print "not " if $result != 0; say "ok # and vs or";

# Test precedence of chained comparison operators
$result = 1 < 2 < 3;
print "not " if $result != 1; say "ok # chained comparison operators";

# Test precedence of bitwise & vs |
$result = 1 | 2 & 3;
print "not " if $result != 3; say "ok # & vs |";

# Test precedence of logical not vs and
$result = not 1 and 2;
print "not " if $result != 0; say "ok # not vs and";

# Test precedence of shift operators vs addition
$result = 1 + 2 << 3;
print "not " if $result != 24; say "ok # << vs +";

## # Test precedence of =~ vs !
## my $string = "hello";
## $result = $string =~ /h/ !~ /x/;
## print "not " if $result != 1; say "ok # =~ vs !~";

## # Test precedence of unary operators vs binary operators
## $result = ~1 + 2;
## $result = $result & 0xFF;
## print "not " if $result != 255; say "ok # ~ vs + $result";

# Test precedence of ternary vs logical or
$result = 0 ? 1 : 2 || 3;
print "not " if $result != 2; say "ok # ?: vs ||";

# Test precedence of = vs not
my $value_not = not 1;
print "not " if $value_not != 0; say "ok # = vs not";

# Test precedence of = vs or
my $result_or;
$result_or = 0 or 1;
print "not " if $result_or != 0; say "ok # = vs or";

# Test precedence of = vs and
my $result_and;
$result_and = 1 and 0;
print "not " if $result_and != 1; say "ok # = vs and";

