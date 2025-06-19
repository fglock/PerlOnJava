use strict;
use warnings;
use Test::More;
use feature 'bitwise';

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

my ($str1, $str2, $result);

# Tests for Perl bitwise string operators

# Bitwise AND (Perl string operator &.)
$str1 = "ABCD";
$result = $str1 &. $str1;
test_bitwise('&.', $str1, $str1, $result, "\x41\x42\x43\x44");

# Bitwise OR (Perl string operator |.)
$str1 = "ABCD";
$result = $str1 |. $str1;
test_bitwise('|.', $str1, $str1, $result, "\x41\x42\x43\x44");

# Bitwise XOR (Perl string operator ^.)
$str1 = "ABCD";
$result = $str1 ^. $str1;
test_bitwise('^.', $str1, $str1, $result, "\x00\x00\x00\x00");

# Bitwise NOT (Perl string operator ~.)
$str1 = "ABCD";
$result = ~. $str1;
test_bitwise('~.', $str1, '', $result, "\xBE\xBD\xBC\xBB");

# Tests for assignment forms of Perl bitwise string operators

# Bitwise AND assignment (Perl string operator &.=)
$str1 = "ABCD";
$str1 &.= "ABCD";
test_bitwise('&.=', "ABCD", "ABCD", $str1, "\x41\x42\x43\x44");

# Bitwise OR assignment (Perl string operator |.=)
$str1 = "ABCD";
$str1 |.= "ABCD";
test_bitwise('|.=', "ABCD", "ABCD", $str1, "\x41\x42\x43\x44");

# Bitwise XOR assignment (Perl string operator ^.=)
$str1 = "ABCD";
$str1 ^.= "ABCD";
test_bitwise('^.=', "ABCD", "ABCD", $str1, "\x00\x00\x00\x00");

done_testing();
