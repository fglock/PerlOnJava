use strict;
use warnings;
use Test::More tests => 4;

# DateTime::Precise uses this idiom in DateTime::Math::fneg.  The vec lvalue
# must retain its extracted numeric value while the compound assignment is
# evaluated; otherwise the operation is dispatched as string XOR.
my $value = "+1E+0";
vec($value, 0, 8) ^= ord('+') ^ ord('-');

is(vec($value, 0, 8), ord('-'), 'compound XOR updates the vec field numerically');
is($value, '-1E+0', 'compound XOR preserves the parent string bytes');

my $other = "A";
vec($other, 0, 8) ^= 3;
is(vec($other, 0, 8), ord('B'), 'a second numeric vec compound assignment works');
is($other, 'B', 'the second parent string is updated');
