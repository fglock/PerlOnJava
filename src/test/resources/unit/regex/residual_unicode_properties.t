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

done_testing;
