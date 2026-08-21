use strict;
use warnings;
use Test::More tests => 7;

my $suffix = chr(0xff) x 2;
my $subject = "ABcd01Xy__--  " . ("\0" x 2) . $suffix;
ok($subject =~ /([[:^ascii:]]+)/, 'POSIX non-ASCII class finds the suffix');
is($1, $suffix, 'capture contains the non-ASCII suffix');
ok($suffix =~ /[[:^ascii:]]/, 'direct non-ASCII scalar matches');
ok(chr(0x80) =~ /[[:^ascii:]]/, 'lower high byte matches');
ok(chr(0xff) =~ /[[:^ascii:]]/, 'upper high byte matches');
ok(chr(0x7f) !~ /[[:^ascii:]]/, 'ASCII boundary does not match complement');
ok(chr(0xff) !~ /[[:ascii:]]/, 'positive ASCII class excludes high byte');
