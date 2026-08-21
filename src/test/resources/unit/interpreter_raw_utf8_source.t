use strict;
use warnings;
use Test::More tests => 8;

our $RAW_UTF8_NEL;
our $RAW_UTF8_NEL_REGEX;
my $fixture = './src/test/resources/unit/fixtures/raw_utf8_nel_source.pl';
open my $source, '<:raw', $fixture or die "Cannot open $fixture: $!";
local $/;
my $source_bytes = <$source>;
close $source;
is(() = $source_bytes =~ /\xC2\x85/g, 2,
    'separate fixture contains two raw UTF-8 NEL byte sequences');

my $loaded = do $fixture;

ok($loaded, 'ASCII harness loads the separate raw UTF-8 source fixture')
    or diag($@ || $!);
is(length($RAW_UTF8_NEL), 1, 'raw UTF-8 NEL decodes to one source character');
is(ord($RAW_UTF8_NEL), 0x85, 'raw UTF-8 NEL preserves its code point');
is(unpack('H*', $RAW_UTF8_NEL), '85', 'decoded NEL retains Perl character bytes');
ok(utf8::is_utf8($RAW_UTF8_NEL), 'raw UTF-8 source retains its Unicode flag');

my $runtime_regex = qr/(?[ [$RAW_UTF8_NEL] ])/;
like($RAW_UTF8_NEL, $runtime_regex,
    'ASCII source can interpolate the decoded character into a regex');
unlike($RAW_UTF8_NEL, $RAW_UTF8_NEL_REGEX,
    'regex parsed from the fixture treats raw NEL as pattern whitespace');
