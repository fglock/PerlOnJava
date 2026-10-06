use strict;
use warnings;

use Test::More tests => 21;
use Scalar::Util qw(dualvar);

my $version = 232;
my $matches = $version =~ tr/.//d;

is($matches, 0, 'no-op transliteration reports no matches');
ok(!utf8::is_utf8($version), 'transliterating a numeric scalar keeps byte semantics');
is($version, '232', 'no-op transliteration preserves the numeric text');

my $formatted = $version =~ /^\d{3}$/ ? "0$version" : $version;
is($formatted, '0232', 'the byte string can be formatted without a UTF-8 flag');

my $changed = 232;
is($changed =~ tr/2/4/, 2, 'transliterating a numeric scalar counts matches');
is($changed, '434', 'transliterating a numeric scalar updates its text');
ok(!utf8::is_utf8($changed), 'changed numeric scalar remains unflagged');

my $non_destructive = 232;
my $non_destructive_result = $non_destructive =~ tr/.//dr;
is($non_destructive_result, '232', 'non-destructive transliteration returns numeric text');
ok(!utf8::is_utf8($non_destructive_result), 'non-destructive numeric result is unflagged');
ok(!utf8::is_utf8($non_destructive), 'non-destructive transliteration leaves its input unflagged');

my $upgraded = pack('C*', 0xe9);
utf8::upgrade($upgraded);
$upgraded =~ tr/x/y/;
ok(utf8::is_utf8($upgraded), 'in-place transliteration retains an existing UTF-8 flag');

my $upgraded_non_destructive = pack('C*', 0xe9);
utf8::upgrade($upgraded_non_destructive);
my $upgraded_result = $upgraded_non_destructive =~ tr/x/y/r;
ok(utf8::is_utf8($upgraded_result), 'non-destructive transliteration retains an existing UTF-8 flag');
ok(utf8::is_utf8($upgraded_non_destructive), 'non-destructive transliteration preserves the flagged input');

my $wide = "\x{100}";
$wide =~ tr/\x{100}/\x{e9}/;
is($wide, "\x{e9}", 'transliteration can map a wide character to Latin-1');
ok(utf8::is_utf8($wide), 'transliteration keeps UTF-8 when the input contains a wide character');

my $dual = dualvar(232, '232');
ok(!utf8::is_utf8($dual), 'dualvar preserves an unflagged string argument');
$dual =~ tr/.//d;
ok(!utf8::is_utf8($dual), 'transliteration preserves an unflagged dualvar string');

my $upgraded_string = pack('C*', 0xe9);
utf8::upgrade($upgraded_string);
my $upgraded_dual = dualvar(232, $upgraded_string);
ok(utf8::is_utf8($upgraded_dual), 'dualvar preserves an upgraded string argument');
$upgraded_dual =~ tr/x/y/;
ok(utf8::is_utf8($upgraded_dual), 'transliteration preserves an upgraded dualvar string');

my $version_string = v0.232;
ok(utf8::is_utf8($version_string), 'version string starts with its UTF-8 flag');
$version_string =~ tr/.//d;
ok(utf8::is_utf8($version_string), 'transliteration preserves a version string UTF-8 flag');
