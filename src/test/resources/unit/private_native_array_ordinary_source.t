use strict;
use warnings;
use Test::More;

my @source = (0x12, 0x34, 0x56, 0x78);
my @derived = ();
for my $i (0 .. 3) {
    $derived[$i] = ($source[$i] << 1) ^ 0x5a;
}

is_deeply(\@derived, [0x7e, 0x32, 0xf6, 0xaa],
    'a fresh private destination accepts guarded ordinary-array word inputs');

done_testing;
