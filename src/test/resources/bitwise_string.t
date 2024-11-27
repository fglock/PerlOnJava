use strict;
use warnings;
use feature 'say';

sub test_result {
    my ($operation, $str1, $str2, $result, $expected) = @_;
    my @result_codes = map { sprintf("U+%04X", ord($_)) } split //, $result;
    my @expected_codes = map { sprintf("U+%04X", ord($_)) } split //, $expected;
    
    if (join(',', @result_codes) eq join(',', @expected_codes)) {
        say "ok # '$str1' $operation '$str2' equals [@expected_codes] <@result_codes>";
    } else {
        say "not ok # '$str1' $operation '$str2' expected [@expected_codes], got [@result_codes]";
    }
}

# Bitwise AND for strings
my $str1 = "ABCD";
my $str2 = "1234";
my $result = $str1 & $str2;
test_result('&', $str1, $str2, $result, "\x01\x02\x03\x04");

# Bitwise OR for strings
$result = $str1 | $str2;
test_result('|', $str1, $str2, $result, "qrst");

# Bitwise XOR for strings
$result = $str1 ^ $str2;
test_result('^', $str1, $str2, $result, "pppp");

# Bitwise NOT for strings
$str1 = "ABCD";
$result = ~$str1;
test_result('~', $str1, '', $result, "\xBE\xBD\xBC\xBB");

# Bitwise AND with different length strings
$str1 = "ABC";
$str2 = "12345";
$result = $str1 & $str2;
test_result('&', $str1, $str2, $result, "\x01\x02\x03");

# Bitwise OR with different length strings
$result = $str1 | $str2;
test_result('|', $str1, $str2, $result, "qrs45");

# Bitwise XOR with different length strings
$result = $str1 ^ $str2;
test_result('^', $str1, $str2, $result, "ppp45");

# Note: Unicode string operations are removed due to the limitation with code points over 0xFF
