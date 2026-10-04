use strict;
use warnings;

use Test::More tests => 4;

my $version = 232;
my $matches = $version =~ tr/.//d;

is($matches, 0, 'no-op transliteration reports no matches');
ok(!utf8::is_utf8($version), 'transliterating a numeric scalar keeps byte semantics');
is($version, '232', 'no-op transliteration preserves the numeric text');

my $formatted = $version =~ /^\d{3}$/ ? "0$version" : $version;
is($formatted, '0232', 'the byte string can be formatted without a UTF-8 flag');
