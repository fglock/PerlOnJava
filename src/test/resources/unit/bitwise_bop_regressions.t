use strict;
use warnings;
use Test::More;

my $wide = "abc\x{100}";

for my $expression (
    '"abc" & "abc\\x{100}"',
    '"abc\\x{100}" & "abc"',
) {
    my $error = eval $expression;
    like($@,
         qr/Use of strings with code points over 0xFF as arguments to bitwise and \(&\) operator is not allowed/,
         "bitwise and rejects a trailing wide character: $expression");
}

eval q{ ~ "\x{100}" };
like($@,
     qr/Use of strings with code points over 0xFF as arguments to 1's complement \(~\) operator is not allowed/,
     "string complement reports Perl's diagnostic");

use feature 'bitwise';
no warnings 'experimental::bitwise', 'pack';
my $utf8_a = substr("a\x{100}", 0, 1);
my $trailing_byte = substr unpack('P2', pack('P', $utf8_a &. 'a')), -1;
is($trailing_byte, "\0", 'UTF-8 string bitwise AND result has a trailing NUL byte');

done_testing;
