use strict;
use warnings;
use Test::More;

# Test basic character range handling
subtest 'Character range tests' => sub {
    # Test basic ASCII range
    my $string = "abcdef";
    my $count = $string =~ tr/a-c/A-C/;
    is($string, "ABCdef", "Basic range a-c to A-C");
    is($count, 3, "Count for basic range");

    # Test with numbers
    $string = "abc123def";
    $count = $string =~ tr/0-9/X/;
    is($string, "abcXXXdef", "Number range 0-9 to X");
    is($count, 3, "Count for number range");

    # Test full byte range
    $string = join '', map chr, 0..255;
    $count = $string =~ tr/\x00-\xff/*/;
    is(length($string), 256, "Full byte range length preserved");
    is($count, 256, "Count for full byte range");

    # Test specific byte ranges
    $string = join '', map chr, 0..255;
    $count = $string =~ tr/\x40-\xbf/X/;
    my $expected = join('', map chr, 0..63) . ('X' x 128) . join('', map chr, 192..255);
    is($string, $expected, "Byte range \x40-\xbf replacement");
    is($count, 128, "Count for byte range \x40-\xbf");
};

# Test complement operations
subtest 'Complement tests' => sub {
    # Basic complement
    my $string = "Hello World";
    my $count = $string =~ tr/a-zA-Z/X/c;
    is($string, "HelloXWorld", "Complement replaces non-letters");
    is($count, 1, "Count for complement");

    # Complement with numbers
    $string = "abc123";
    $count = $string =~ tr/0-9/X/c;
    is($string, "XXX123", "Complement keeps numbers");
    is($count, 3, "Count for complement with numbers");

    # Complement with empty replacement (should map to self)
    $string = "abc123";
    $count = $string =~ tr/0-9//c;
    is($string, "abc123", "Complement with empty replacement");
    is($count, 3, "Count for complement with empty replacement");
};

# Test delete operations
subtest 'Delete tests' => sub {
    # Basic delete
    my $string = "Hello World";
    my $count = $string =~ tr/l//d;
    is($string, "Heo Word", "Delete 'l' characters");
    is($count, 3, "Count for delete");

    # Delete with range
    $string = "abc123def";
    $count = $string =~ tr/0-9//d;
    is($string, "abcdef", "Delete number range");
    is($count, 3, "Count for delete range");

    # Delete with nothing to delete
    $string = "abcdef";
    $count = $string =~ tr/0-9//d;
    is($string, "abcdef", "Delete with no matches");
    is($count, 0, "Count for delete with no matches");
};

# Test complement + delete
subtest 'Complement + delete tests' => sub {
    # Keep only letters
    my $string = "Hello World 123!";
    my $count = $string =~ tr/a-zA-Z//cd;
    is($string, "HelloWorld", "Complement + delete keeps only letters");
    is($count, 6, "Count for complement + delete");

    # Keep only numbers
    $string = "abc123def456";
    $count = $string =~ tr/0-9//cd;
    is($string, "123456", "Complement + delete keeps only numbers");
    is($count, 6, "Count for complement + delete with numbers");

    # Test with byte range - this is the failing test from tr.t
    $string = join '', map chr, 0..255;
    $count = $string =~ tr/\x40-\xbf//cd;
    my $expected = join '', map chr, 0x40..0xbf;
    is($string, $expected, "Complement + delete with byte range");
    is($count, 128, "Count should be 128 (256 - 128)");
};

# Test squeeze operations
subtest 'Squeeze tests' => sub {
    # Basic squeeze
    my $string = "Hellooo";
    my $count = $string =~ tr/o/o/s;
    is($string, "Hello", "Squeeze repeated 'o'");
    is($count, 3, "Count for squeeze");

    # Squeeze with replacement
    $string = "Hellooo";
    $count = $string =~ tr/o/0/s;
    is($string, "Hell0", "Squeeze with replacement");
    is($count, 3, "Count for squeeze with replacement");

    # Squeeze multiple characters
    $string = "aabbccdd";
    $count = $string =~ tr/a-d/A-D/s;
    is($string, "ABCD", "Squeeze range");
    is($count, 8, "Count for squeeze range");
};

# Test edge cases
subtest 'Edge cases' => sub {
    # Empty string
    my $string = "";
    my $count = $string =~ tr/a-z/A-Z/;
    is($string, "", "Empty string remains empty");
    is($count, 0, "Count for empty string");

    # Single character
    $string = "a";
    $count = $string =~ tr/a/A/;
    is($string, "A", "Single character translation");
    is($count, 1, "Count for single character");

    # No matches
    $string = "123";
    $count = $string =~ tr/a-z/A-Z/;
    is($string, "123", "No matches leaves string unchanged");
    is($count, 0, "Count for no matches");

    # Overlapping ranges
    $string = "abcdef";
    $count = $string =~ tr/a-c/A-C/;
    is($string, "ABCdef", "First range application");
    $count = $string =~ tr/b-d/X/;
    is($string, "ABCXef", "Overlapping range on modified string");
};

# Test with special characters
subtest 'Special character tests' => sub {
    # Newlines and tabs
    my $string = "Hello\tWorld\n";
    my $count = $string =~ tr/\t\n/  /;
    is($string, "Hello World ", "Tab and newline to spaces");
    is($count, 2, "Count for special characters");

    # Null bytes
    $string = "Hello\0World";
    $count = $string =~ tr/\0/-/;
    is($string, "Hello-World", "Null byte replacement");
    is($count, 1, "Count for null byte");
};

# Test Unicode/high-byte characters
subtest 'Unicode and high-byte tests' => sub {
    # Basic Unicode character
    my $string = "café";
    my $count = $string =~ tr/é/e/;
    is($string, "cafee", "Unicode character replacement");
    is($count, 2, "Count for Unicode replacement");

    # High-byte characters (Latin-1)
    $string = "\x{00}\x{7f}\x{80}\x{ff}";
    $count = $string =~ tr/\x{80}-\x{ff}/X/;
    is($string, "\x{00}\x{7f}XX", "High-byte character replacement");
    is($count, 2, "Count for high-byte replacement");

    # Mix of ASCII and high-byte
    $string = "Hello\x{ff}World";
    $count = $string =~ tr/\x{ff}/!/;
    is($string, "Hello!World", "Mixed ASCII and high-byte");
    is($count, 1, "Count for mixed replacement");
};

# Test error conditions (these should be in eval blocks)
subtest 'Error condition tests' => sub {
    # Invalid range (min > max) - this should fail
    my $err;
    eval q{
        my $string = "test";
        $string =~ tr/z-a/Z-A/;
    };
    $err = $@;
    like($err, qr/Invalid range "z-a"/, "Invalid range z-a should produce error");

    # Test with \x{101}-\x{100} (another invalid range)
    eval q{
        my $string = "test";
        $string =~ tr/\x{101}-\x{100}//;
    };
    $err = $@;
    like($err, qr/Invalid range/, "Invalid range \\x{101}-\\x{100} should produce error");

    # Test with very long strings
    my $long_string = "a" x 1000;
    my $count = $long_string =~ tr/a/b/;
    is($long_string, "b" x 1000, "Long string translation");
    is($count, 1000, "Count for long string");
};

# Test combinations of modifiers
subtest 'Modifier combination tests' => sub {
    # Complement + squeeze
    my $string = "Hello   World";
    my $count = $string =~ tr/a-zA-Z/X/cs;
    is($string, "HelloXWorld", "Complement + squeeze");
    is($count, 3, "Count for complement + squeeze");

    # Delete + squeeze (delete takes precedence)
    $string = "Hellooo";
    $count = $string =~ tr/o//ds;
    is($string, "Hell", "Delete + squeeze");
    is($count, 3, "Count for delete + squeeze");

    # All three: complement + delete + squeeze
    $string = "Hello   World!!!";
    $count = $string =~ tr/a-zA-Z//cds;
    is($string, "HelloWorld", "Complement + delete + squeeze");
    is($count, 6, "Count for c+d+s");
};

# Test specific issues from tr.t
subtest 'Specific tr.t issues' => sub {
    # Test the specific failing case with all bytes
    my $all255 = join '', map chr, 0..0xff;
    my $s = $all255;
    my $c = $s =~ tr/\x40-\xbf//c;
    is($s, $all255, "/c with no replacement should not modify string");
    is($c, 128, "/c should count 128 characters (0-63 and 192-255)");

    # Now test with delete
    $s = $all255;
    $c = $s =~ tr/\x40-\xbf//cd;
    my $expected = join '', map chr, 0x40..0xbf;
    is($s, $expected, "/cd should keep only characters in range");
    is($c, 128, "/cd should count 128 deleted characters");
};

done_testing();
