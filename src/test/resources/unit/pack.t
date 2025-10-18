use feature 'say';
use strict;
use warnings;
use Test::More;

###################
# Perl pack Operator Tests

subtest 'Basic formats' => sub {
    # Test unsigned char (C)
    my $packed = pack('C', 255);
    is($packed, "\xFF", "Unsigned char (C)");

    # Test unsigned short (S)
    $packed = pack('S', 65535);
    is($packed, "\xFF\xFF", "Unsigned short (S)");

    # Test unsigned long (L)
    $packed = pack('L', 4294967295);
    is($packed, "\xFF\xFF\xFF\xFF", "Unsigned long (L)");
};

subtest 'Endianness formats' => sub {
    # Test 32-bit big-endian (N)
    my $packed = pack('N', 4294967295);
    is($packed, "\xFF\xFF\xFF\xFF", "32-bit big-endian (N)");

    # Test 32-bit little-endian (V)
    $packed = pack('V', 4294967295);
    is($packed, "\xFF\xFF\xFF\xFF", "32-bit little-endian (V)");

    # Test 16-bit big-endian (n)
    $packed = pack('n', 65535);
    is($packed, "\xFF\xFF", "16-bit big-endian (n)");

    # Test 16-bit little-endian (v)
    $packed = pack('v', 65535);
    is($packed, "\xFF\xFF", "16-bit little-endian (v)");
};

subtest 'String formats' => sub {
    # Test null-padded string (a)
    my $packed = pack('a5', 'abc');
    is($packed, "abc\0\0", "Null-padded string (a)");

    # Test space-padded string (A)
    $packed = pack('A5', 'abc');
    is($packed, "abc  ", "Space-padded string (A)");

    # Test null-terminated string (Z)
    $packed = pack('Z5', 'abc');
    is($packed, "abc\0\0", "Null-terminated string (Z)");
};

subtest 'Bit string formats' => sub {
    # Test bit string (b)
    my $packed = pack('b8', '10101010');
    is($packed, "U", "Bit string (b)");

    # Test bit string (B)
    $packed = pack('B8', '10101010');
    is($packed, "\xAA", "Bit string (B)");
};

subtest 'Multiple values and repeat counts' => sub {
    # Test multiple values
    my $packed = pack('C2S2', 1, 2, 3, 4);
    is($packed, "\x01\x02\x03\x00\x04\x00", "Multiple values (C2S2)");

    # Test repeat count
    $packed = pack('C3', 1, 2, 3);
    is($packed, "\x01\x02\x03", "Repeat count (C3)");
};

subtest 'Star modifier tests' => sub {
    # Test C* with multiple values
    my $packed = pack('C*', 1, 2, 3, 4, 5);
    is($packed, "\x01\x02\x03\x04\x05", "C* packs all bytes");

    # Test S* with multiple values
    $packed = pack('S*', 256, 512, 1024);
    is($packed, "\x00\x01\x00\x02\x00\x04", "S* packs all shorts");

    # Test a* with string
    $packed = pack('a*', 'hello world');
    is($packed, 'hello world', "a* packs entire string");

    # Test A* with string
    $packed = pack('A*', 'hello');
    is($packed, 'hello', "A* packs entire string without padding");

    # Test Z* with string
    $packed = pack('Z*', 'hello');
    is($packed, "hello\0", "Z* packs string with null terminator");

    # Test mixed format with *
    $packed = pack('C2S*', 1, 2, 256, 512, 1024);
    is($packed, "\x01\x02\x00\x01\x00\x02\x00\x04", "Mixed format C2S*");

    # Test empty * (no remaining values)
    $packed = pack('C*');
    is($packed, '', "C* with no values produces empty string");

    # Test N* with multiple values
    $packed = pack('N*', 0x12345678, 0xABCDEF00);
    is($packed, "\x12\x34\x56\x78\xAB\xCD\xEF\x00", "N* packs all 32-bit big-endian");

    # Test n* with multiple values
    $packed = pack('n*', 0x1234, 0x5678, 0xABCD);
    is($packed, "\x12\x34\x56\x78\xAB\xCD", "n* packs all 16-bit big-endian");

    # Test B* with bit string
    $packed = pack('B*', '1010101111001100');
    is($packed, "\xAB\xCC", "B* packs entire bit string");

    # Test b* with bit string
    $packed = pack('b*', '1010101111001100');
    is($packed, "\xD5\x33", "b* packs entire bit string (LSB first)");
};

subtest 'Error handling' => sub {
    # Test unsupported format character
    eval { my $packed = pack('X', 1); };
    ok($@, "Unsupported format character throws error");
};

done_testing();
