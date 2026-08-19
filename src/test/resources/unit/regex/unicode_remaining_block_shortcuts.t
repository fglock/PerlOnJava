use strict;
use warnings;
use Test::More;

no warnings qw(non_unicode surrogate);

sub property_pattern {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    ok(defined $pattern, "$property compiles") or diag($@);
    return $pattern;
}

for my $case (
    ['IsHighSurrogates',           0xD800, 0xDB7F, 0xDB80],
    ['IsHighPrivateUseSurrogates', 0xDB80, 0xDBFF, 0xDB7F],
    ['IsHighPUSurrogates',         0xDB80, 0xDBFF, 0xDC00],
    ['IsLowSurrogates',            0xDC00, 0xDFFF, 0xDBFF],
) {
    my ($property, $first, $last, $outside) = @$case;
    my $pattern = property_pattern($property);
    like(chr($first), $pattern, "$property includes its first surrogate");
    like(chr($last), $pattern, "$property includes its last surrogate");
    unlike(chr($outside), $pattern, "$property excludes the adjacent block");
}

my $idc = property_pattern('_is_IDC');
like('A', $idc, 'IsIDC keeps binary ID_Continue precedence');
like(chr(0xFE00), $idc, 'IsIDC includes an ID_Continue variation selector');
unlike(chr(0x2FF0), $idc, 'IsIDC does not resolve the same-spelled block');

for my $property (' -VS', '_-is_VS') {
    my $pattern = property_pattern($property);
    like(chr(0xFE00), $pattern, "$property includes BMP variation selectors");
    like(chr(0xE0100), $pattern, "$property includes supplementary variation selectors");
    unlike('A', $pattern, "$property excludes ordinary letters");
}

done_testing;
