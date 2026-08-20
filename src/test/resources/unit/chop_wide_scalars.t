use strict;
use warnings;
no warnings 'portable';
use Test::More tests => 6;

my $high = chr(0x80000001) . chr(0x80000000);
is(chop($high), chr(0x80000000), 'chop returns a beyond-Unicode scalar');
is($high, chr(0x80000001), 'chop removes one beyond-Unicode scalar');

my $highest = chr(0x7fffffffffffffff) . chr(0x7ffffffffffffffe);
is(chop($highest), chr(0x7ffffffffffffffe), 'chop returns the highest signed-IV scalar');
is($highest, chr(0x7fffffffffffffff), 'chop preserves the preceding highest scalar');

my $supplementary = "A" . chr(0x1f642);
is(chop($supplementary), chr(0x1f642), 'chop returns one supplementary scalar');
is($supplementary, 'A', 'chop removes one supplementary scalar');
