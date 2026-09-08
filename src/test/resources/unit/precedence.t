use strict;
use Test::More;
use feature 'say';

# Test precedence of ++ vs ->
my ($flush_mro, $pkg);
$flush_mro = {a => 2};
$pkg = "a";
++$flush_mro->{$pkg};
ok(!($flush_mro->{a} != 3), '++ vs ->');

# Test precedence of ** vs unary -
my $result = -2**2;
ok(!($result != -4), '** vs unary -');

# Prefix ! must let a following named unary operator consume multiplicative
# expressions.  This is the form used by Class::MakeMethods to test whether
# a constructor returned an even-sized method/name list.
my @even_results = ('new', sub {});
my @odd_results = ('new', sub {}, 'extra');
ok(! scalar @even_results % (1 + 1), '! scalar @array % divisor groups modulo with scalar');
ok(!(! scalar @odd_results % (1 + 1)), '! scalar @array % divisor preserves the odd case');

# keys retains its single-aggregate operand boundary: the multiplication is
# applied to the key count, not to the hash operand.
my %precedence_keys = (first => 1, second => 2);
is(keys(%precedence_keys) * 4, 8, 'keys %hash stops before multiplication');

# Test precedence of * vs +
$result = 2 + 3 * 4;
ok(!($result != 14), '* vs +');

# Test precedence of && vs ||
$result = 0 || 1 && 0;
ok(!($result != 0), '&& vs ||');

# Test precedence of ?: vs =
my $value;
$value = 1 ? 2 : 3;
ok(!($value != 2), '?: vs =');

# Test precedence of , vs =>
my @array = (1, 2, 3);
my %hash = (a => 1, b => 2, c => 3);
my $comma_result = (1, 2, 3);
ok(!($comma_result != 3), ', vs =>');

# Test precedence of and vs or
$result = 0 or 1 and 0;
ok(!($result != 0), 'and vs or');

# Test precedence of chained comparison operators
$result = 1 < 2 < 3;
ok(!($result != 1), 'chained comparison operators');

# Test precedence of bitwise & vs |
$result = 1 | 2 & 3;
ok(!($result != 3), '& vs |');

# Test precedence of logical not vs and
$result = not 1 and 2;
ok(!($result != 0), 'not vs and');

# Test precedence of shift operators vs addition
$result = 1 + 2 << 3;
ok(!($result != 24), '<< vs +');

## # Test precedence of =~ vs !
## my $string = "hello";
## $result = $string =~ /h/ !~ /x/;
## ok(!($result != 1), '=~ vs !~');

## # Test precedence of unary operators vs binary operators
## $result = ~1 + 2;
## $result = $result & 0xFF;
## ok(!($result != 255), '~ vs + $result');

# Test precedence of ternary vs logical or
$result = 0 ? 1 : 2 || 3;
ok(!($result != 2), '?: vs ||');

# Test precedence of = vs not
my $value_not = not 1;
ok(!($value_not != 0), '= vs not');

# Test precedence of = vs or
my $result_or;
$result_or = 0 or 1;
ok(!($result_or != 0), '= vs or');

# Test precedence of = vs and
my $result_and;
$result_and = 1 and 0;
ok(!($result_and != 1), '= vs and');

done_testing();
