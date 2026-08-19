use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

for my $property (qw(
    GraphemeLink GrLink
    OtherAlphabetic OAlpha
    OtherDefaultIgnorableCodePoint ODI
    OtherGraphemeExtend OGrExt
    OtherIDContinue OIDC
    OtherIDStart OIDS
    OtherLowercase OLower
    OtherMath OMath
    OtherUppercase OUpper
)) {
    my ($pattern, $error) = compile_property($property);
    ok(!defined($pattern) && length($error), "$property is rejected as obsolete");
}

my ($space, $space_error) = compile_property('White space');
ok(defined $space, 'Whitespace loose alias compiles') or diag($space_error);
like(chr(0x00A0), $space, 'Whitespace includes Unicode no-break space');
unlike('A', $space, 'Whitespace excludes letters');

my ($print, $print_error) = compile_property('Print');
ok(defined $print, 'Print compiles') or diag($print_error);
like(chr(0x10FFFD), $print, 'Print includes private-use characters');
unlike(chr(0xE01F0), $print, 'Print excludes unassigned characters');

my ($xdigit, $xdigit_error) = compile_property('XDigit');
ok(defined $xdigit, 'XDigit compiles') or diag($xdigit_error);
like(chr(0xFF46), $xdigit, 'XDigit includes fullwidth hexadecimal f');
unlike(chr(0xFF47), $xdigit, 'XDigit excludes fullwidth g');

done_testing;
