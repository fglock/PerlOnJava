use feature 'say';
use strict;
use warnings;
use Test::More;
use utf8;

# Set up UTF-8 output for test results
binmode(STDOUT, ":utf8");
binmode(STDERR, ":utf8");

###################
# Pack/Unpack C0 and U0 Mode Tests

subtest 'Basic C0 vs U0 mode differences' => sub {
    # Test with a Unicode character (Greek alpha α = U+03B1)
    my $alpha = "\x{3B1}";
    
    # C0 mode (character mode) - default
    my @chars_c0 = unpack("C*", $alpha);
    is_deeply(\@chars_c0, [0x3B1], "C0 mode: C* unpacks character value");
    
    # U0 mode (UTF-8 byte mode)
    my @chars_u0 = unpack("U0C*", $alpha);
    is_deeply(\@chars_u0, [0xCE, 0xB1], "U0 mode: C* unpacks UTF-8 bytes");
};

subtest 'Mode switching mid-format' => sub {
    my $str = "\x{3B1}\x{3C9}"; # αω
    
    # The string has 2 characters but 4 UTF-8 bytes
    # C reads first character (α = 0x3B1)
    # U0C2 switches to byte mode and reads 2 bytes from remaining data
    my @values = unpack("C U0C2", $str);
    is($values[0], 0x3B1, "First C in character mode");
    is($values[1], 0xCF, "U0C gets first UTF-8 byte of ω");
    is($values[2], 0x89, "U0C gets second UTF-8 byte of ω");
    is(scalar(@values), 3, "Only 3 values extracted");
};

subtest 'String formats with modes' => sub {
    my $unicode = "Hello α";
    
    # a* format in C0 mode - need to encode first
    my $utf8_encoded = $unicode;
    utf8::encode($utf8_encoded);
    
    my $packed_c0 = pack("C0a*", $utf8_encoded);
    is($packed_c0, $utf8_encoded, "C0a* preserves UTF-8 encoded string");
    
    # Unpacking differences
    my $utf8_bytes = "Hello \xCE\xB1"; # UTF-8 encoded
    my ($str_c0) = unpack("C0a*", $utf8_bytes);
    is($str_c0, $utf8_bytes, "C0a* unpacks raw bytes");
    
    # Test with A format
    my $packed_A = pack("A10", "test");
    is($packed_A, "test      ", "A10 pads with spaces");
};

subtest 'Unicode format (U) interactions' => sub {
    # U format is different - it reads UTF-8 encoded data
    # When the string is already UTF-8 flagged, U just reads values
    my $str = "\x{3B1}\x{3C9}"; # αω with UTF-8 flag
    
    # U reads Unicode codepoints from UTF-8 flagged string
    my @unicode = unpack("U*", $str);
    is_deeply(\@unicode, [0x3B1, 0x3C9], "U* reads Unicode codepoints");
    
    # Pack Unicode values - U packs as Unicode codepoints
    my $packed = pack("U*", 0x3B1, 0x3C9);
    # This creates a UTF-8 flagged string
    my @codepoints = unpack("U*", $packed);
    is_deeply(\@codepoints, [0x3B1, 0x3C9], "U* round-trip works");
};

subtest 'Mixed formats and mode persistence' => sub {
    # Use simple ASCII string
    my $data = "ABC";
    
    # Mode persistence test
    my @mixed = unpack("U0 C C C", $data);
    is($mixed[0], 0x41, "U0 mode: A");
    is($mixed[1], 0x42, "U0 mode: B");
    is($mixed[2], 0x43, "U0 mode: C");
    
    # Reset with C0
    @mixed = unpack("U0C C0C C", $data);
    is($mixed[0], 0x41, "U0C gets byte");
    is($mixed[1], 0x42, "C0C in character mode");
    is($mixed[2], 0x43, "Following C");
};

subtest 'Group boundaries and mode scope' => sub {
    # Use a longer string to have data for all formats
    my $str = "\x{3B1}\x{3C9}\x{3B2}"; # αωβ
    
    # Groups don't isolate mode changes in Perl
    my @grouped = unpack("C (U0C2) C", $str);
    is($grouped[0], 0x3B1, "C before group in character mode");
    is($grouped[1], 0xCF, "U0C inside group - first byte of ω");
    is($grouped[2], 0x89, "Second byte inside group");
    is($grouped[3], 946, "C after group still in U0 mode - first byte of β");
};

subtest 'Bit string formats with modes' => sub {
    my $char = "\xFF"; # Single byte 0xFF
    
    # B format in character mode
    my $bits_c0 = unpack("B8", $char);
    is($bits_c0, "11111111", "B8 gets 8 bits");
    
    # Test with Unicode character
    my $unicode = "\x{3B1}";
    my $bits_unicode = unpack("U0B16", $unicode);
    is($bits_unicode, "1100111010110001", "U0B16 gets UTF-8 byte bits");
};

subtest 'Default mode behavior' => sub {
    # Default is C0 unless format starts with U
    my $str = "\x{3B1}";
    
    # Default C0
    my @default = unpack("C", $str);
    is($default[0], 0x3B1, "Default mode is C0");
    
    # U format with UTF-8 flagged string
    my @u_mode = unpack("U C", $str);
    is(scalar(@u_mode), 1, "Only 1 value extracted");
    is($u_mode[0], 0x3B1, "U format reads Unicode codepoint");

    # Test with explicit modes
    my @explicit = unpack("U0 C2", $str);
    is_deeply(\@explicit, [0xCE, 0xB1], "U0 at start sets byte mode");
};

subtest 'Pack mode behavior' => sub {
    # Pack with values as bytes
    my $packed_bytes = pack("C*", 0xCE, 0xB1);
    is($packed_bytes, "\xCE\xB1", "C* packs values as bytes");
    
    # Pack Unicode codepoints with U
    my $packed_unicode = pack("U*", 0x3B1);
    # U* creates a UTF-8 encoded string with UTF-8 flag
    my @codepoints = unpack("U*", $packed_unicode);
    is_deeply(\@codepoints, [0x3B1], "U* packs and unpacks Unicode correctly");
    
    # Test N format with modes
    my $packed_N = pack("N", 0x12345678);
    is($packed_N, "\x12\x34\x56\x78", "N packs 32-bit big-endian");
};

subtest 'Complex format strings' => sub {
    my $data = "ABCD";
    
    # Multiple mode switches with simple ASCII
    my @complex = unpack("C U0C C0C U0C", $data);
    is($complex[0], 0x41, "A in character mode");
    is($complex[1], 0x42, "B in U0 byte mode");
    is($complex[2], 0x43, "C in C0 character mode");
    is($complex[3], 0x44, "D in U0 byte mode");
    
    # Test with star modifier
    my @star = unpack("U0C2 C0C*", $data);
    is($star[0], 0x41, "U0 mode byte A");
    is($star[1], 0x42, "U0 mode byte B");
    is($star[2], 0x43, "C0 mode C");
    is($star[3], 0x44, "C0 mode D");
};

subtest 'Edge cases' => sub {
    # Empty string
    my @empty_c0 = unpack("C0C*", "");
    is_deeply(\@empty_c0, [], "C0C* on empty string");
    
    my @empty_u0 = unpack("U0C*", "");
    is_deeply(\@empty_u0, [], "U0C* on empty string");
    
    # Mode at very end
    my @end_mode = unpack("C", "\x{3B1}");
    is_deeply(\@end_mode, [0x3B1], "Single C unpacks character");
    
    # U format with non-UTF8 bytes - it treats them as Latin-1
    my $latin1 = pack("C", 0xFF);
    my @u_latin1 = unpack("U", $latin1);
    is_deeply(\@u_latin1, [0xFF], "U format reads Latin-1 bytes as codepoints");
    
    # C* reads bytes as-is
    my $bytes = pack("C", 0xCE);
    my @bytes_read = unpack("C*", $bytes);
    is_deeply(\@bytes_read, [0xCE], "C* reads bytes as-is");
};

subtest 'Numeric formats with modes' => sub {
    # Create actual byte string
    my $bytes = pack("C*", 0xCE, 0xB1, 0xCF, 0x89);
    
    # 16-bit formats in U0 mode (byte mode)
    my @n = unpack("n", $bytes);
    is($n[0], 0xCEB1, "n reads 16-bit big-endian from bytes");
    
    # Test with v (little-endian)
    my @v = unpack("v", $bytes);
    is($v[0], 0xB1CE, "v reads 16-bit little-endian from bytes");
    
    # 32-bit formats
    my @N = unpack("N", $bytes);
    is($N[0], 0xCEB1CF89, "N reads 32-bit big-endian from bytes");
};

subtest 'Documentation examples' => sub {
    # Example showing C0 vs U0 difference
    my $greek = "\x{3B1}\x{3C9}"; # αω
    
    # C0A* - character mode
    my ($str1) = unpack("C0A*", $greek);
    is($str1, $greek, "C0A* preserves Unicode string");
    
    # Get hex representation of characters
    my @chars = unpack("C0C*", $greek);
    is(sprintf("%04X.%04X", @chars), "03B1.03C9", "C0C* gets Unicode codepoints");
    
    # Get hex representation of UTF-8 bytes
    my @bytes = unpack("U0C*", $greek);
    is(sprintf("%02X.%02X.%02X.%02X", @bytes), "CE.B1.CF.89", "U0C* gets UTF-8 bytes");
};

done_testing();
