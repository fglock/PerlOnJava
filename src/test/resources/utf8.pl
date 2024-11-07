use strict;
use feature 'say';
use utf8;

# Test utf8::upgrade
my $string = "A";
my $num_octets = utf8::upgrade($string);
print "not " if $num_octets != 1; say "ok # utf8::upgrade";

# Test utf8::downgrade
$string = "\x{100}";
my $success = 0;
eval { utf8::downgrade($string, 0); $success = 1; };
print "not " if $success; say "ok # utf8::downgrade";

$string = "\x{100}";
$success = utf8::downgrade($string, 1);
print "not " if $success; say "ok # utf8::downgrade";

# Test utf8::encode
$string = "\x{100}";
utf8::encode($string);
print "not " if $string ne "\xc4\x80"; say "ok # utf8::encode";

# Test utf8::decode
$string = "\xc4\x80";
utf8::decode($string);
print "not " if $string ne "\x{100}"; say "ok # utf8::decode";

# Test utf8::native_to_unicode
my $unicode = utf8::native_to_unicode(ord('A'));
print "not " if $unicode != 65; say "ok # utf8::native_to_unicode";

# Test utf8::unicode_to_native
my $native = utf8::unicode_to_native(65);
print "not " if $native != 65; say "ok # utf8::unicode_to_native";

# Test utf8::is_utf8
$string = "A";
utf8::upgrade($string);
my $flag = utf8::is_utf8($string);
print "not " if !$flag; say "ok # utf8::is_utf8";

# Test utf8::valid
$string = "\x{100}";
$flag = utf8::valid($string);
print "not " if !$flag; say "ok # utf8::valid";

