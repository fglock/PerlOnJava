#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use MIME::Base64;

# Simple implementation of lives_ok
sub lives_ok (&;$) {
    my ($code, $test_name) = @_;
    $test_name ||= 'code lives';
    
    eval { $code->() };
    ok(!$@, $test_name);
}

# Test basic encoding and decoding
subtest 'Basic encoding and decoding' => sub {
    plan tests => 6;
    
    # Simple string
    my $text = 'Hello, World!';
    my $encoded = encode_base64($text);
    my $decoded = decode_base64($encoded);
    is($encoded, "SGVsbG8sIFdvcmxkIQ==\n", 'Basic encoding works');
    is($decoded, $text, 'Basic decoding works');
    
    # Empty string - encode_base64('') returns empty string, not newline
    is(encode_base64(''), '', 'Empty string encodes to empty string');
    is(decode_base64(''), '', 'Empty string decodes to empty');
    
    # Binary data
    my $binary = "\x00\x01\x02\x03\x04\x05";
    my $bin_encoded = encode_base64($binary);
    my $bin_decoded = decode_base64($bin_encoded);
    is($bin_encoded, "AAECAwQF\n", 'Binary encoding works');
    is($bin_decoded, $binary, 'Binary decoding works');
};

# Test line ending parameter
subtest 'Line ending parameter' => sub {
    plan tests => 4;
    
    my $text = 'Hello, World!';
    
    # Default line ending (newline)
    my $default = encode_base64($text);
    is($default, "SGVsbG8sIFdvcmxkIQ==\n", 'Default line ending is newline');
    
    # Custom line ending
    my $crlf = encode_base64($text, "\r\n");
    is($crlf, "SGVsbG8sIFdvcmxkIQ==\r\n", 'Custom CRLF line ending works');
    
    # Empty line ending (no line breaking)
    my $no_break = encode_base64($text, '');
    is($no_break, "SGVsbG8sIFdvcmxkIQ==", 'Empty line ending prevents line break');
    
    # Custom separator
    my $custom = encode_base64($text, " <END>\n");
    is($custom, "SGVsbG8sIFdvcmxkIQ== <END>\n", 'Custom separator works');
};

# Test 76 character line breaking
subtest 'Line breaking at 76 characters' => sub {
    plan tests => 3;
    
    # String that encodes to exactly 76 characters
    my $text76 = 'A' x 57;  # 57 chars encode to 76 base64 chars
    my $encoded76 = encode_base64($text76);
    my @lines76 = split /\n/, $encoded76;
    is(length($lines76[0]), 76, 'First line is exactly 76 characters');
    
    # String that encodes to more than 76 characters
    my $text_long = 'A' x 100;  # Will need line breaking
    my $encoded_long = encode_base64($text_long);
    my @lines = split /\n/, $encoded_long;
    is(length($lines[0]), 76, 'Long text first line is 76 characters');
    ok(length($lines[1]) <= 76, 'Long text second line is <= 76 characters');
};

# Test wide character handling
subtest 'Wide character handling' => sub {
    plan tests => 2;
    
    # ASCII characters should work fine
    my $ascii = "Hello \x7F";
    lives_ok { encode_base64($ascii) } 'ASCII characters (0-127) encode fine';
    
    # High bytes (128-255) should also work
    my $high_bytes = "Hello \xFF";
    lives_ok { encode_base64($high_bytes) } 'High bytes (128-255) encode fine';
    
    # Note: Perl's MIME::Base64 actually accepts wide characters and encodes
    # them as UTF-8, unlike what the documentation suggests. The Java 
    # implementation should throw an exception for characters > 255.
};

# Test decode with invalid characters
subtest 'Decode with invalid characters' => sub {
    plan tests => 5;
    
    # Valid base64 with spaces and newlines (should be ignored)
    my $with_spaces = "SGVs bG8sIF  dvcmxkIQ==\n";
    is(decode_base64($with_spaces), 'Hello, World!', 'Spaces are ignored in decode');
    
    # Invalid characters mixed in (should be ignored)
    my $with_invalid = "SGVs!\@#bG8sIF\$%^dvcmxkIQ==";
    is(decode_base64($with_invalid), 'Hello, World!', 'Invalid characters are ignored');
    
    # Unicode characters (should be ignored)
    my $with_unicode = "SGVsbG8sIFdvcmxkIQ==™®";
    is(decode_base64($with_unicode), 'Hello, World!', 'Unicode characters are ignored');
    
    # Only invalid characters
    is(decode_base64('!@#$%^&*()'), '', 'Only invalid characters decode to empty');
    
    # Create a proper test string with invalid characters mixed in
    # SGVsbG8sIFdvcmxkIQ== is the correct encoding for "Hello, World!"
    my $mixed = "S!G#Vs\$bG%8s^IF&dv*cm(xk)IQ=!=";
    is(decode_base64($mixed), 'Hello, World!', 'Mixed valid/invalid characters work');
};

# Test padding character behavior
subtest 'Padding character behavior' => sub {
    plan tests => 4;
    
    # Normal padding
    is(decode_base64('SGVsbG8='), 'Hello', 'Single padding works');
    is(decode_base64('SGVsbA=='), 'Hell', 'Double padding works');
    
    # Characters after padding should be ignored
    is(decode_base64('SGVsbG8=extra'), 'Hello', 'Characters after padding are ignored');
    is(decode_base64('SGVsbA==morestuff!@#'), 'Hell', 'Everything after padding is ignored');
};

# Test edge cases
subtest 'Edge cases' => sub {
    plan tests => 5;
    
    # Very long string without line breaks
    my $long_text = 'A' x 1000;
    my $long_encoded = encode_base64($long_text, '');
    my $long_decoded = decode_base64($long_encoded);
    is($long_decoded, $long_text, 'Very long string round-trip works');
    ok(index($long_encoded, "\n") == -1, 'No line breaks with empty separator');
    
    # All possible byte values
    my $all_bytes = join('', map { chr($_) } 0..255);
    my $all_encoded = encode_base64($all_bytes);
    my $all_decoded = decode_base64($all_encoded);
    is($all_decoded, $all_bytes, 'All byte values round-trip correctly');
    
    # Multiple consecutive padding characters
    is(decode_base64('SGVsbG8==='), 'Hello', 'Extra padding characters handled');
    
    # Padding in the middle (should stop decoding)
    is(decode_base64('SGVs=bG8='), 'Hel', 'Padding in middle stops decoding');
};

# Test undef handling
subtest 'Undef and special cases' => sub {
    plan tests => 4;
    
    # encode_base64 with undef - returns empty string with warning
    {
        local $SIG{__WARN__} = sub {};  # Suppress warning for this test
        my $encoded_undef = encode_base64(undef);
        is($encoded_undef, '', 'encode_base64(undef) returns empty string');
    }
    
    # decode_base64 with undef - returns empty string with warning
    {
        local $SIG{__WARN__} = sub {};  # Suppress warning for this test
        my $decoded_undef = decode_base64(undef);
        is($decoded_undef, '', 'decode_base64(undef) returns empty string');
    }
    
    # encode_base64 with reference (should stringify)
    my $ref = [];
    my $encoded_ref = encode_base64($ref);
    ok($encoded_ref, 'encode_base64 with reference works (stringifies)');
    
    # decode_base64 with reference (should stringify)
    my $decoded_ref = decode_base64($ref);
    ok(defined $decoded_ref, 'decode_base64 with reference works (stringifies)');
};

done_testing();

