use strict;
use warnings;
use utf8;
no warnings 'experimental::uniprop_wildcards';
use Test::More;

sub property_matches {
    my ($scalar, $property) = @_;
    return eval '$scalar =~ /\\p{' . $property . '}/';
}

ok(property_matches("\x{094D}", 'InCB=Linker'), 'InCB Linker matches');
ok(property_matches("\x{0915}", 'Indic_Conjunct_Break=Consonant'),
    'long InCB alias matches');
ok(property_matches("\x{0300}", 'InCB=:\AExtend\z:'),
    'InCB wildcard matches a value');
ok(property_matches('A', 'InCB=None'), 'InCB missing default matches');

ok(property_matches("\x{0300}", 'GCB=Extend'), 'GCB Extend matches');
ok(property_matches("\x{0600}", 'Grapheme_Cluster_Break=Prepend'),
    'long GCB alias matches');
ok(eval q{qr/\p{GCB=:\AE_Modifier\z:}/; 1},
    'GCB wildcard accepts a retained empty value');
ok(property_matches('A', 'GCB=Other'), 'GCB missing default matches');

ok(property_matches('A', 'Identifier_Type=Recommended'),
    'Identifier_Type Recommended matches');
SKIP: {
    skip 'Not_Character alias requires current Identifier_Type data', 2
        unless eval q{qr/\p{ID_Type=Not_Character}/; 1};
    ok(property_matches("\x{0378}", 'ID_Type=Not_Character'),
        'ID_Type default matches an unassigned scalar');
    ok(!property_matches('A', 'Identifier_Type=Not_Character'),
        'Identifier_Type default excludes explicit records');
}
ok(property_matches('A', 'Identifier_Type=:\ARecommended\z:'),
    'Identifier_Type wildcard matches');

ok(property_matches("\x{FF46}", 'Hex=True'), 'Hex includes fullwidth f');
ok(!property_matches("\x{FF46}", 'Hex=False'), 'Hex false excludes fullwidth f');
ok(property_matches("\x{FF46}", 'Hex=:\AY\z:'),
    'Hex wildcard uses Perl Hex data');
ok(!property_matches('G', 'Hex'), 'bare Hex excludes non-hex letters');

SKIP: {
    my $supported = eval q{qr/\p{kEH_Core=C}/; 1};
    skip 'kEH_Core requires Unicode 17 data', 5 unless $supported;
    ok(property_matches("\x{13000}", 'kEH_Core=C'), 'kEH core value matches');
    ok(property_matches("\x{1305D}", 'Is_kEH_Core=L'), 'Is_kEH_Core legacy value matches');
    ok(property_matches('A', 'kEH_Core=N'), 'kEH_Core missing default matches');
    ok(!property_matches("\x{13000}", 'kEH_Core=N'),
        'kEH_Core default excludes explicit core records');
    ok(property_matches("\x{1305D}", 'kEH_Core=:\AL\z:'),
        'kEH_Core wildcard matches');
}

SKIP: {
    my $is_perlonjava = eval {
        require PerlOnJava::Process;
        PerlOnJava::Process::_is_perlonjava_runtime();
    } || 0;
    my $unicode_version = eval {
        require Unicode::UCD;
        Unicode::UCD::UnicodeVersion();
    } // 'unknown';
    skip "standard Perl uses Unicode $unicode_version", 8
        if !$is_perlonjava && $unicode_version lt '18.0.0';

    ok(chr(0x1DB1B) =~ qr/\p{Bidi_Mirrored=Yes}/,
        'UCD 18 Bidi_Mirrored includes U+1DB1B');
    ok(chr(0x1B168) =~ qr/\p{Line_Break=Conditional_Japanese_Starter}/,
        'UCD 18 Line_Break includes U+1B168');
    ok(chr(0x324DF) =~ qr/\p{Numeric_Type=Numeric}/,
        'UCD 18 Numeric_Type includes U+324DF');
    ok(chr(0x3FC3F) =~ qr/\p{Sentence_Break=OLetter}/,
        'UCD 18 Sentence_Break includes U+3FC3F');
    ok(chr(0x1B168) =~ qr/\p{Word_Break=Katakana}/,
        'UCD 18 Word_Break includes U+1B168');
    ok(chr(0x3FC3F) =~ qr/\p{XPosixAlpha}/,
        'UCD 18 XPosixAlpha includes U+3FC3F');
    ok(chr(0x3FC3F) =~ qr/\p{XPosixAlnum}/,
        'UCD 18 XPosixAlnum includes U+3FC3F');
    ok(chr(0x3FC3F) =~ qr/\p{Is_Alnum}/,
        'UCD 18 Is_Alnum includes U+3FC3F');
}

done_testing;
