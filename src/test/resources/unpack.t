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

done_testing();
