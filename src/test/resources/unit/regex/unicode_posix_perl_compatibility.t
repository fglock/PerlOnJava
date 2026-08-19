use strict;
use warnings;
use Test::More;

sub property_pattern {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    ok(defined $pattern, "$property compiles") or diag($@);
    return $pattern;
}

for my $property ('PerlSpace', 'IsPerlSpace') {
    my $pattern = property_pattern($property);
    like("\t", $pattern, "$property includes ASCII tab");
    unlike(chr(0x00A0), $pattern, "$property excludes non-ASCII space");
}

for my $property ('PerlWord', 'IsPerlWord') {
    my $pattern = property_pattern($property);
    like('_', $pattern, "$property includes ASCII underscore");
    unlike(chr(0x00E9), $pattern, "$property excludes non-ASCII letters");
}

for my $property ('XPerlSpace', 'Is_XPerlSpace', 'SpacePerl', 'Is-SpacePerl') {
    my $pattern = property_pattern($property);
    like(chr(0x00A0), $pattern, "$property includes Unicode no-break space");
    unlike('A', $pattern, "$property excludes letters");
}

for my $property ('XPosixGraph', 'Is_XPosixGraph') {
    my $pattern = property_pattern($property);
    like(chr(0x10FFFD), $pattern, "$property includes private-use characters");
    like(chr(0x200B), $pattern, "$property includes format characters");
    unlike(chr(0xE01F0), $pattern, "$property excludes unassigned characters");
    unlike(' ', $pattern, "$property excludes separators");
}

for my $property ('XPosixPrint', 'Is_XPosixPrint') {
    my $pattern = property_pattern($property);
    like(chr(0x10FFFD), $pattern, "$property includes private-use characters");
    like(chr(0x00A0), $pattern, "$property includes space separators");
    unlike(chr(0xE01F0), $pattern, "$property excludes unassigned characters");
    unlike(chr(0x2028), $pattern, "$property excludes line separators");
}

my $xdigit = property_pattern('Is_XPosixXDigit');
like(chr(0xFF46), $xdigit, 'XPosixXDigit includes fullwidth hexadecimal f');
unlike(chr(0xFF47), $xdigit, 'XPosixXDigit excludes fullwidth g');

done_testing;
