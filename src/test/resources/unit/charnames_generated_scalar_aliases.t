use strict;
use warnings;
use Test::More;

{
    use charnames ':full';
    is(ord("\N{SOFT HYPHEN}"), 0x00AD, 'canonical scalar name resolves');
    is(ord("\N{SHY}"), 0x00AD, 'abbreviation alias resolves');
    is(ord("\N{LINE FEED (LF)}"), 0x000A, 'control alias resolves');
    is(ord("\N{LATIN CAPITAL LETTER GHA}"), 0x01A2,
        'correction alias resolves');
    is(ord("\N{BOM}"), 0xFEFF, 'alternate alias resolves');
    is(ord("\N{CJK UNIFIED IDEOGRAPH-4E00}"), 0x4E00,
        'algorithmic CJK name remains available');
    is(ord("\N{HANGUL SYLLABLE GA}"), 0xAC00,
        'algorithmic Hangul name remains available');
    is_deeply([map ord, split //, "\N{KEYCAP DIGIT NINE}"],
        [0x0039, 0xFE0F, 0x20E3], 'named sequence remains multi-scalar');
}

{
    use charnames ':loose';
    is(ord("\N{latin-capital_letter a}"), 0x0041,
        'loose full-name matching remains active');
}

my $strict_loose = eval q{use charnames ':full'; "\N{latin-capital_letter a}"};
ok(!defined $strict_loose, ':full rejects a loose spelling');
like($@, qr/Unknown charname/, ':full rejection keeps Perl diagnostic');

my $unknown = eval q{use charnames ':full'; "\N{NOT A CHARACTER NAME}"};
ok(!defined $unknown, 'unknown standard name stays unresolved');
like($@, qr/Unknown charname/, 'unknown standard name keeps Perl diagnostic');

my $custom;
{
    use lib 'src/test/resources/unit/lib';
    use Phase36Cname;
    $custom = "\N{CUSTOM VALUE}";
}
is($custom, 'CUSTOM VALUE', 'custom lexical translator stays authoritative');

done_testing();
