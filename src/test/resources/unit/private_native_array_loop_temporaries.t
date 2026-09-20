use strict;
use warnings;
use Test::More;

my @source = (0x12, 0x34, 0x56, 0x78);
my @derived = ();
for my $i (0 .. 3) {
    my $left = $source[($i - 1) % @source];
    my $cell = $source[$i];
    my $right = $source[($i + 1) % @source];
    $derived[$i] = (($cell << 1) ^ ($left >> 1) ^ ($right & 1)) & 0xffff_ffff;
}

is_deeply(\@derived, [24, 97, 182, 219],
    'ordered loop-local native-word temporaries feed a fresh destination');

done_testing;
