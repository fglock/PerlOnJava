use strict;
use warnings;
use POSIX qw(LC_CTYPE setlocale);
use Test::More tests => 2;

my $locale = setlocale(LC_CTYPE);
ok(defined $locale && length $locale,
    'the process starts with a defined LC_CTYPE locale');

my $upper = "\N{LATIN CAPITAL LETTER A WITH GRAVE}";
my $lower = "\N{LATIN SMALL LETTER A WITH GRAVE}";
my $matched = $lower =~ /[$upper]/il ? 1 : 0;
my $utf8_locale = $locale =~ /UTF-?8/i ? 1 : 0;
is($matched, $utf8_locale,
    'locale regex folding follows the process LC_CTYPE encoding');
