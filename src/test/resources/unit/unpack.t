use feature 'say';
use strict;
use warnings;
use Test::More;

# Test unsigned char (C)
my $packed = pack('C', 255);
my $unpacked = unpack('C', $packed);
is($unpacked, 255, 'Unsigned char (C)');

# Test unsigned short (S)
$packed = pack('S', 65535);
$unpacked = unpack('S', $packed);
is($unpacked, 65535, 'Unsigned short (S)');

# Test unsigned long (L)
$packed = pack('L', 4294967295);
$unpacked = unpack('L', $packed);
is($unpacked, 4294967295, 'Unsigned long (L)');

# Test big-endian long (N)
$packed = pack('N', 4294967295);
$unpacked = unpack('N', $packed);
is($unpacked, 4294967295, 'Big-endian long (N)');

# Test little-endian long (V)
$packed = pack('V', 4294967295);
$unpacked = unpack('V', $packed);
is($unpacked, 4294967295, 'Little-endian long (V)');

# Test big-endian short (n)
$packed = pack('n', 65535);
$unpacked = unpack('n', $packed);
is($unpacked, 65535, 'Big-endian short (n)');

# Test little-endian short (v)
$packed = pack('v', 65535);
$unpacked = unpack('v', $packed);
is($unpacked, 65535, 'Little-endian short (v)');

# Test float (f)
$packed = pack('f', 3.14);
$unpacked = unpack('f', $packed);
cmp_ok(abs($unpacked - 3.14), '<', 0.0001, "Float (f) value $unpacked");

# Test double (d)
$packed = pack('d', 3.14159265358979);
$unpacked = unpack('d', $packed);
cmp_ok(abs($unpacked - 3.14159265358979), '<', 0.00000000000001, "Double (d) value $unpacked");

# Test string (a)
$packed = pack('a5', 'hello');
$unpacked = unpack('a5', $packed);
is($unpacked, 'hello', 'String (a)');

# Test string with null padding (A)
$packed = pack('A5', 'hi');
$unpacked = unpack('A5', $packed);
is($unpacked, 'hi', 'String with null padding (A)');

# Test null-terminated string (Z)
$packed = pack('Z5', 'hi');
$unpacked = unpack('Z5', $packed);
is($unpacked, 'hi', 'Null-terminated string (Z)');

# Test bit string (b)
$packed = pack('b8', '10101010');
$unpacked = unpack('b8', $packed);
is($unpacked, '10101010', 'Bit string (b)');

# Test bit string (B)
$packed = pack('B8', '10101010');
$unpacked = unpack('B8', $packed);
is($unpacked, '10101010', 'Bit string (B)');

# Test multiple values
$packed = pack('C S L', 255, 65535, 4294967295);
my ($c, $s, $l) = unpack('C S L', $packed);
is($c, 255, 'Multiple values - C');
is($s, 65535, 'Multiple values - S');
is($l, 4294967295, 'Multiple values - L');

# Test repeat count
$packed = pack('C3', 1, 2, 3);
my @unpacked = unpack('C3', $packed);
is_deeply(\@unpacked, [1, 2, 3], 'Repeat count (C3)');

# Test * modifier
subtest 'Star (*) modifier tests' => sub {
    # Test C* - unpack all bytes
    my $packed = pack('C5', 10, 20, 30, 40, 50);
    my @unpacked = unpack('C*', $packed);
    is_deeply(\@unpacked, [10, 20, 30, 40, 50], 'C* unpacks all bytes');

    # Test S* - unpack all shorts
    $packed = pack('S4', 1000, 2000, 3000, 4000);
    @unpacked = unpack('S*', $packed);
    is_deeply(\@unpacked, [1000, 2000, 3000, 4000], 'S* unpacks all shorts');

    # Test L* - unpack all longs
    $packed = pack('L3', 100000, 200000, 300000);
    @unpacked = unpack('L*', $packed);
    is_deeply(\@unpacked, [100000, 200000, 300000], 'L* unpacks all longs');

    # Test a* - unpack rest as string
    $packed = pack('a10', 'Hello Perl');
    my $str = unpack('a*', $packed);
    is($str, 'Hello Perl', 'a* unpacks entire string');

    # Test A* - unpack rest as space-padded string (strips trailing spaces)
    $packed = pack('A10', 'Hi');
    $str = unpack('A*', $packed);
    is($str, 'Hi', 'A* unpacks and strips trailing spaces');

    # Test Z* - unpack rest as null-terminated string
    $packed = pack('Z10', 'Hello');
    $str = unpack('Z*', $packed);
    is($str, 'Hello', 'Z* unpacks null-terminated string');

    # Test mixed patterns with *
    $packed = pack('C2 S3', 10, 20, 100, 200, 300);
    @unpacked = unpack('C2 S*', $packed);
    is_deeply(\@unpacked, [10, 20, 100, 200, 300], 'Mixed pattern C2 S*');

    # Test * with no remaining data
    $packed = pack('C2', 10, 20);
    @unpacked = unpack('C2 S*', $packed);
    is_deeply(\@unpacked, [10, 20], 'S* with no remaining data returns empty');

    # Test n* and v* (big/little endian shorts)
    $packed = pack('n3', 256, 512, 1024);
    @unpacked = unpack('n*', $packed);
    is_deeply(\@unpacked, [256, 512, 1024], 'n* unpacks all big-endian shorts');

    $packed = pack('v3', 256, 512, 1024);
    @unpacked = unpack('v*', $packed);
    is_deeply(\@unpacked, [256, 512, 1024], 'v* unpacks all little-endian shorts');

    # Test N* and V* (big/little endian longs)
    $packed = pack('N2', 65536, 131072);
    @unpacked = unpack('N*', $packed);
    is_deeply(\@unpacked, [65536, 131072], 'N* unpacks all big-endian longs');

    $packed = pack('V2', 65536, 131072);
    @unpacked = unpack('V*', $packed);
    is_deeply(\@unpacked, [65536, 131072], 'V* unpacks all little-endian longs');

    # Test f* and d* (floats and doubles)
    $packed = pack('f3', 1.1, 2.2, 3.3);
    @unpacked = unpack('f*', $packed);
    is(scalar @unpacked, 3, 'f* unpacks correct number of floats');
    cmp_ok(abs($unpacked[0] - 1.1), '<', 0.0001, 'First float value');
    cmp_ok(abs($unpacked[1] - 2.2), '<', 0.0001, 'Second float value');
    cmp_ok(abs($unpacked[2] - 3.3), '<', 0.0001, 'Third float value');

    $packed = pack('d2', 1.11111111, 2.22222222);
    @unpacked = unpack('d*', $packed);
    is(scalar @unpacked, 2, 'd* unpacks correct number of doubles');
    cmp_ok(abs($unpacked[0] - 1.11111111), '<', 0.00000001, 'First double value');
    cmp_ok(abs($unpacked[1] - 2.22222222), '<', 0.00000001, 'Second double value');
};

done_testing();
