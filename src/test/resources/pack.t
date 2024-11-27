use feature 'say';
use strict;
use warnings;

###################
# Perl pack Operator Tests

# Test unsigned char (C)
my $packed = pack('C', 255);
print "not " if $packed ne "\xFF";
say "ok # Unsigned char (C)";

# Test unsigned short (S)
$packed = pack('S', 65535);
print "not " if $packed ne "\xFF\xFF";
say "ok # Unsigned short (S)";

# Test unsigned long (L)
$packed = pack('L', 4294967295);
print "not " if $packed ne "\xFF\xFF\xFF\xFF";
say "ok # Unsigned long (L)";

# Test 32-bit big-endian (N)
$packed = pack('N', 4294967295);
print "not " if $packed ne "\xFF\xFF\xFF\xFF";
say "ok # 32-bit big-endian (N)";

# Test 32-bit little-endian (V)
$packed = pack('V', 4294967295);
print "not " if $packed ne "\xFF\xFF\xFF\xFF";
say "ok # 32-bit little-endian (V)";

# Test 16-bit big-endian (n)
$packed = pack('n', 65535);
print "not " if $packed ne "\xFF\xFF";
say "ok # 16-bit big-endian (n)";

# Test 16-bit little-endian (v)
$packed = pack('v', 65535);
print "not " if $packed ne "\xFF\xFF";
say "ok # 16-bit little-endian (v)";

## # Test 32-bit float (f)
## $packed = pack('f', 1.0);
## print "not " if unpack('f', $packed) != 1.0;
## say "ok # 32-bit float (f)";
## 
## # Test 64-bit double (d)
## $packed = pack('d', 1.0);
## print "not " if unpack('d', $packed) != 1.0;
## say "ok # 64-bit double (d)";

# Test null-padded string (a)
$packed = pack('a5', 'abc');
print "not " if $packed ne "abc\0\0";
say "ok # Null-padded string (a)";

# Test space-padded string (A)
$packed = pack('A5', 'abc');
print "not " if $packed ne "abc  ";
say "ok # Space-padded string (A)";

# Test null-terminated string (Z)
$packed = pack('Z5', 'abc');
print "not " if $packed ne "abc\0\0";
say "ok # Null-terminated string (Z)";

# Test bit string (b)
$packed = pack('b8', '10101010');
print "not " if $packed ne "U";
say "ok # Bit string (b) <$packed>";

# Test bit string (B)
$packed = pack('B8', '10101010');
print "not " if $packed ne "\xAA";
say "ok # Bit string (B)";

# Test multiple values
$packed = pack('C2S2', 1, 2, 3, 4);
print "not " if $packed ne "\x01\x02\x03\x00\x04\x00";
say "ok # Multiple values (C2S2)";

# Test repeat count
$packed = pack('C3', 1, 2, 3);
print "not " if $packed ne "\x01\x02\x03";
say "ok # Repeat count (C3)";

## # Test insufficient arguments
## eval { $packed = pack('C3', 1, 2); };
## print "not " if $@ eq '';
## say "ok # Insufficient arguments <$@> <$packed>";

# Test unsupported format character
eval { $packed = pack('X', 1); };
print "not " if $@ eq '';
say "ok # Unsupported format character";


