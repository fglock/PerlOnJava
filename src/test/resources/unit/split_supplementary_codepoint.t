use strict;
use warnings;

use Test::More tests => 4;

my $whale = chr(0x1f433);
my @characters = split //, $whale;

is(scalar @characters, 1,
   'split with an empty pattern returns one supplementary code point');
is(ord($characters[0]), 0x1f433,
   'split with an empty pattern preserves the supplementary code point');
is(join('', @characters), $whale,
   'split supplementary code points round-trips through join');
is_deeply([map { ord } split //, "A${whale}B"], [0x41, 0x1f433, 0x42],
   'split mixes BMP and supplementary code points without surrogate pieces');
