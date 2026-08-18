use strict;
use warnings;
use utf8;
use Test::More tests => 11;

ok("A" =~ /\N{LATIN CAPITAL LETTER A}/,
    'official named character resolves outside a class');
ok("A" =~ /[\N{LATIN CAPITAL LETTER A}]/,
    'official named character resolves inside a class');
ok("\x{1F642}" =~ /\N{U+1F642}/,
    'supplementary named character resolves outside a class');
ok("\x{1F642}" =~ /[\N{U+1F642}]/,
    'supplementary named character resolves inside a class');
ok("a" =~ /\N{LATIN CAPITAL LETTER A}/i,
    'case folding applies after named-character resolution');
ok('#' =~ /(?x:\N{NUMBER SIGN})/,
    'named punctuation remains significant under extended syntax');
ok(' ' =~ /(?x:[\N{SPACE}])/,
    'named whitespace remains significant in an extended class');

{
    use bytes;
    my $latin1 = pack('C', 0xE9);
    ok($latin1 =~ /\N{U+00E9}/,
        'named character uses the active single-byte regex mode');
}

eval q{qr/\N{}/};
like($@, qr/(?:Unknown charname|zero length)/,
    'empty named-character escape remains fatal');

eval q{qr/\N{NOT A REAL UNICODE CHARACTER}/};
like($@, qr/(?:Unknown charname|Invalid Unicode character name)/,
    'resolver failure remains fatal');

my $unterminated = '\\N{LATIN CAPITAL LETTER A';
eval { qr/$unterminated/ };
like($@, qr/(?:terminator|right brace|terminated)/i,
    'unterminated named-character escape remains fatal');
