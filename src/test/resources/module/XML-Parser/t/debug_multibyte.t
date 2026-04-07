use strict;
use warnings;

use Test::More;
use XML::Parser;
use IO::File;

# Test that Debug style's Char handler correctly escapes multibyte
# (CJK/Chinese) characters as whole code points, not individual bytes.
# Regression test for GH#25 / rt.cpan.org#5721.

# U+4E16 (世) = \xe4\xb8\x96, U+754C (界) = \xe7\x95\x8c
my $cjk_xml = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
    . qq(<doc>\xe4\xb8\x96\xe7\x95\x8c</doc>);
utf8::downgrade($cjk_xml);

# Capture STDERR output from Debug style
my $tmpfile = IO::File->new_tmpfile();
open( my $olderr, ">&", \*STDERR ) or die "Cannot dup STDERR: $!";
open( STDERR, ">&", $tmpfile->fileno ) or die "Cannot redirect STDERR: $!";

my $parser = XML::Parser->new( Style => 'Debug' );
$parser->parse($cjk_xml);

open( STDERR, ">&", $olderr ) or die "Cannot restore STDERR: $!";
close($olderr);

# Read captured output
seek( $tmpfile, 0, 0 );
my $output = do { local $/; <$tmpfile> };
close($tmpfile);

# The Char line should contain #x4E16; and #x754C; (Unicode code points)
# not byte-level escapes like #xE4;#xB8;#x96;#xE7;#x95;#x8C;
like( $output, qr/#x4E16;/,
    'Debug Char: U+4E16 (世) escaped as whole code point' );
like( $output, qr/#x754C;/,
    'Debug Char: U+754C (界) escaped as whole code point' );

# Must NOT contain byte-level escapes (first byte of 世 is 0xE4)
unlike( $output, qr/#xE4;/,
    'Debug Char: no byte-level escape for multibyte character' );

# Also test with Latin-1 range non-ASCII (é = U+00E9)
my $latin_xml = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
    . qq(<doc>caf\xc3\xa9</doc>);
utf8::downgrade($latin_xml);

$tmpfile = IO::File->new_tmpfile();
open( $olderr, ">&", \*STDERR ) or die "Cannot dup STDERR: $!";
open( STDERR, ">&", $tmpfile->fileno ) or die "Cannot redirect STDERR: $!";

$parser->parse($latin_xml);

open( STDERR, ">&", $olderr ) or die "Cannot restore STDERR: $!";
close($olderr);

seek( $tmpfile, 0, 0 );
$output = do { local $/; <$tmpfile> };
close($tmpfile);

like( $output, qr/#xE9;/,
    'Debug Char: U+00E9 (é) escaped as code point' );
like( $output, qr/caf#xE9;/,
    'Debug Char: ASCII chars preserved alongside escaped non-ASCII' );

done_testing();
