use strict;
use warnings;
use Test::More tests => 12;

for my $case (
    [ ''   => 0, ''     ],
    [ '0'  => 1, '30'   ],
    [ '12' => 2, '3132' ],
) {
    my ($input, $length, $hex) = @$case;
    my $unpacked = unpack('a4', $input);
    is(length($unpacked), $length, "a4 does not pad input of length $length");
    is(unpack('H*', $unpacked), $hex, "a4 preserves available bytes for length $length");
}

my @chunks = unpack('(a4)*', '123456');
is(scalar @chunks, 2, 'repeated group returns a partial final chunk');
is(unpack('H*', $chunks[0]), '31323334', 'repeated group returns the full chunk');
is(unpack('H*', $chunks[1]), '3536', 'repeated group does not pad the final chunk');

my $binary = "\xff\xff\xff\0";
my $binary_chunk = unpack('a4', $binary);
is(length($binary_chunk), 4, 'a4 returns four high-byte characters');
{
    use bytes;
    is(length($binary_chunk), 4, 'a4 preserves the byte-string flag');
}
is(unpack('H*', $binary_chunk), 'ffffff00', 'a4 preserves high bytes exactly');
