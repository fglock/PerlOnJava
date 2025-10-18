use strict;
use feature 'say';
use utf8;
use Test::More;

# Test utf8::upgrade
my $string = "A";
my $num_octets = utf8::upgrade($string);
is($num_octets, 1, 'utf8::upgrade returns correct number of octets');

# Test utf8::downgrade
$string = "\x{100}";
my $success = 0;
eval { utf8::downgrade($string, 0); $success = 1; };
ok(!$success, 'utf8::downgrade fails on wide character without FAIL_OK');

$string = "\x{100}";
$success = utf8::downgrade($string, 1);
ok(!$success, 'utf8::downgrade returns false on wide character with FAIL_OK');

# Test utf8::encode
$string = "\x{100}";
utf8::encode($string);
is($string, "\xc4\x80", 'utf8::encode converts to UTF-8 bytes');

# Test utf8::decode
$string = "\xc4\x80";
utf8::decode($string);
is($string, "\x{100}", 'utf8::decode converts from UTF-8 bytes');

# Test utf8::native_to_unicode
my $unicode = utf8::native_to_unicode(ord('A'));
is($unicode, 65, 'utf8::native_to_unicode returns correct value');

# Test utf8::unicode_to_native
my $native = utf8::unicode_to_native(65);
is($native, 65, 'utf8::unicode_to_native returns correct value');

# Test utf8::is_utf8
$string = "A";
utf8::upgrade($string);
my $flag = utf8::is_utf8($string);
ok($flag, 'utf8::is_utf8 returns true for upgraded string');

# Test utf8::valid
$string = "\x{100}";
$flag = utf8::valid($string);
ok($flag, 'utf8::valid returns true for valid UTF-8 string');

done_testing();
