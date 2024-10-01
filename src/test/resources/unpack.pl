use feature 'say';
use strict;
use warnings;

###################
# Perl unpack Operator Tests

# Test unsigned char (C)
my $packed = pack('C', 255);
my $unpacked = unpack('C', $packed);
print "not " if $unpacked != 255;
say "ok # Unsigned char (C)";

# Test unsigned short (S)
$packed = pack('S', 65535);
$unpacked = unpack('S', $packed);
print "not " if $unpacked != 65535;
say "ok # Unsigned short (S)";

# Test unsigned long (L)
$packed = pack('L', 4294967295);
$unpacked = unpack('L', $packed);
print "not " if $unpacked != 4294967295;
say "ok # Unsigned long (L)";

# Test big-endian long (N)
$packed = pack('N', 4294967295);
$unpacked = unpack('N', $packed);
print "not " if $unpacked != 4294967295;
say "ok # Big-endian long (N)";

# Test little-endian long (V)
$packed = pack('V', 4294967295);
$unpacked = unpack('V', $packed);
print "not " if $unpacked != 4294967295;
say "ok # Little-endian long (V)";

# Test big-endian short (n)
$packed = pack('n', 65535);
$unpacked = unpack('n', $packed);
print "not " if $unpacked != 65535;
say "ok # Big-endian short (n)";

# Test little-endian short (v)
$packed = pack('v', 65535);
$unpacked = unpack('v', $packed);
print "not " if $unpacked != 65535;
say "ok # Little-endian short (v)";

# Test float (f)
$packed = pack('f', 3.14);
$unpacked = unpack('f', $packed);
print "not " if abs($unpacked - 3.14) > 0.0001;
say "ok # Float (f) <$unpacked>";

# Test double (d)
$packed = pack('d', 3.14159265358979);
$unpacked = unpack('d', $packed);
print "not " if abs($unpacked - 3.14159265358979) > 0.00000000000001;
say "ok # Double (d) <$unpacked>";

# Test string (a)
$packed = pack('a5', 'hello');
$unpacked = unpack('a5', $packed);
print "not " if $unpacked ne 'hello';
say "ok # String (a)";

# Test string with null padding (A)
$packed = pack('A5', 'hi');
$unpacked = unpack('A5', $packed);
print "not " if $unpacked ne 'hi';
say "ok # String with null padding (A)";

# Test null-terminated string (Z)
$packed = pack('Z5', 'hi');
$unpacked = unpack('Z5', $packed);
print "not " if $unpacked ne 'hi';
say "ok # Null-terminated string (Z)";

# Test bit string (b)
$packed = pack('b8', '10101010');
$unpacked = unpack('b8', $packed);
print "not " if $unpacked ne '10101010';
say "ok # Bit string (b)";

# Test bit string (B)
$packed = pack('B8', '10101010');
$unpacked = unpack('B8', $packed);
print "not " if $unpacked ne '10101010';
say "ok # Bit string (B)";

# Test multiple values
$packed = pack('C S L', 255, 65535, 4294967295);
my ($c, $s, $l) = unpack('C S L', $packed);
print "not " if $c != 255 || $s != 65535 || $l != 4294967295;
say "ok # Multiple values (C S L)";

# Test repeat count
$packed = pack('C3', 1, 2, 3);
my @unpacked = unpack('C3', $packed);
print "not " if "@unpacked" ne "1 2 3";
say "ok # Repeat count (C3)";
