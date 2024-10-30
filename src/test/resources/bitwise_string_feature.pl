use strict;
use warnings;
use feature 'say';
use feature 'bitwise';

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

my ($str1, $str2, $result);

# Tests for Perl bitwise string operators

# Bitwise AND (Perl string operator &.)
$str1 = "ABCD";
$result = $str1 &. $str1;
test_result('&.', $str1, $str1, $result, "\x41\x42\x43\x44");

# Bitwise OR (Perl string operator |.)
$str1 = "ABCD";
$result = $str1 |. $str1;
test_result('|.', $str1, $str1, $result, "\x41\x42\x43\x44");

# Bitwise XOR (Perl string operator ^.)
$str1 = "ABCD";
$result = $str1 ^. $str1;
test_result('^.', $str1, $str1, $result, "\x00\x00\x00\x00");

# Bitwise NOT (Perl string operator ~.)
$str1 = "ABCD";
$result = ~. $str1;
test_result('~.', $str1, '', $result, "\xBE\xBD\xBC\xBB");
