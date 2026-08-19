use strict;
use warnings;
use Test::More;

sub Is_31_Bit_Super { return "110000\t7FFFFFFF\n" }
sub Is_Portable_Super { return '!utf8::Any' }

{
    no warnings qw(non_unicode portable overflow);
    my $inside = chr(0x7FFF_FFFF);
    my $outside = chr(hex('7FFF_FFFF_FFFF_FFFE'));

    like($inside, qr/^\p{Is_31_Bit_Super}$/,
        'property includes its 31-bit upper endpoint');
    unlike($outside, qr/^\p{Is_31_Bit_Super}$/,
        'property excludes a signed-IV scalar above its upper endpoint');
    like($outside, qr/^\p{Is_Portable_Super}$/,
        'portable complement includes the signed-IV scalar');
    unlike($inside, qr/^\P{Is_31_Bit_Super}$/,
        'property complement excludes the upper endpoint');
}

done_testing;
