use strict;
use warnings;
use utf8;
use Test::More;

my @cases = (
    [Can  => Canonical => 0x00C0],
    [Com  => Compat    => 0x00A8],
    [Enc  => Circle    => 0x2460],
    [Fin  => Final     => 0xFB51],
    [Font => Font      => 0x2102],
    [Fra  => Fraction  => 0x00BC],
    [Init => Initial   => 0xFB54],
    [Iso  => Isolated  => 0xFB50],
    [Med  => Medial    => 0xFB55],
    [Nar  => Narrow    => 0xFF61],
    [Nb   => Nobreak   => 0x00A0],
    [None => None      => 0x0041],
    [Sml  => Small     => 0xFE50],
    [Sqr  => Square    => 0x3250],
    [Sub  => Sub       => 0x1D62],
    [Sup  => Super     => 0x00AA],
    [Vert => Vertical  => 0x309F],
    [Wide => Wide      => 0x3000],
);

sub matches_property {
    my ($property, $value, $code_point) = @_;
    my $regex = eval "qr/\\p{$property=$value}/";
    return defined($regex) && chr($code_point) =~ $regex;
}

TODO: {
    local $TODO = 'Decomposition_Type resolver wiring is a separate Phase 36 slice';

    for my $case (@cases) {
        my ($short, $long, $code_point) = @$case;
        ok(matches_property('dt', $short, $code_point),
            "short Decomposition_Type value alias $short");
        ok(matches_property('Decomposition_Type', $long, $code_point),
            "long Decomposition_Type value alias $long");
    }

    ok(matches_property('de composition-type', 'c_a-n', 0x00C0),
        'property and value aliases use Perl loose matching');
    ok(!matches_property('dt', 'None', 0x00C0),
        'the ordered missing-value default does not override explicit ranges');

    my $none = eval 'qr/\p{dt=None}/';
    ok(defined($none) && chr(0x10FFFF) =~ $none,
        'the missing-value default covers the Unicode maximum');

    ok(matches_property('dt', 'NonCanon', 0x00A8),
        'Perl short non-canonical union value is valid');
    ok(matches_property('Decomposition Type', 'Non_Canonical', 0x2460),
        'Perl long non-canonical union value uses loose matching');
    ok(matches_property('Is_Dt', 'Can', 0x00C0),
        'Perl Is_ prefix accepts the short property alias');
    ok(matches_property('Is Decomposition Type', 'Wide', 0x3000),
        'Perl Is_ prefix accepts the long property alias loosely');
    ok(!matches_property('is_dt', 'Can', 0x00C0),
        'Perl Is prefix remains case-sensitive');
    ok(!matches_property("Decomposition\x{1680}Type", 'Can', 0x00C0),
        'Perl loose matching does not ignore Unicode whitespace');
}

done_testing;
