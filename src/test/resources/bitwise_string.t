use strict;
use warnings;
use Test::More;

sub test_bitwise {
    my ($operation, $str1, $str2, $result, $expected) = @_;
    my @result_codes = map { sprintf("U+%04X", ord($_)) } split //, $result;
    my @expected_codes = map { sprintf("U+%04X", ord($_)) } split //, $expected;

    is(
        join(',', @result_codes),
        join(',', @expected_codes),
        "'$str1' $operation '$str2' equals [@expected_codes]"
    );
}

# Bitwise AND for strings
my $str1 = "ABCD";
my $str2 = "1234";
my $result = $str1 & $str2;
test_bitwise('&', $str1, $str2, $result, "\x01\x02\x03\x04");

# Bitwise OR for strings
$result = $str1 | $str2;
test_bitwise('|', $str1, $str2, $result, "qrst");

# Bitwise XOR for strings
$result = $str1 ^ $str2;
test_bitwise('^', $str1, $str2, $result, "pppp");

# Bitwise NOT for strings
$str1 = "ABCD";
$result = ~$str1;
test_bitwise('~', $str1, '', $result, "\xBE\xBD\xBC\xBB");

# Bitwise AND with different length strings
$str1 = "ABC";
$str2 = "12345";
$result = $str1 & $str2;
test_bitwise('&', $str1, $str2, $result, "\x01\x02\x03");

# Bitwise OR with different length strings
$result = $str1 | $str2;
test_bitwise('|', $str1, $str2, $result, "qrs45");

# Bitwise XOR with different length strings
$result = $str1 ^ $str2;
test_bitwise('^', $str1, $str2, $result, "ppp45");

done_testing();
