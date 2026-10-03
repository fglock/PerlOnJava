use strict;
use warnings;
use Test::More;

my $positive_infinity = 'Inf' + 0;
my $negative_infinity = '-Inf' + 0;
my $nan = 'NaN' + 0;

is(sprintf('%f', $positive_infinity), 'Inf', '%f formats positive infinity');
is(sprintf('%f', $negative_infinity), '-Inf', '%f formats negative infinity');
is(sprintf('%F', $positive_infinity), 'Inf', '%F formats positive infinity');
is(sprintf('%+8.2f', $positive_infinity), '    +Inf', 'flags and width apply to infinity');
is(sprintf('%f', $nan), 'NaN', '%f formats NaN');
is(sprintf('%d', $positive_infinity), 'Inf', 'integer decimal conversion preserves infinity');
is(sprintf('%x', $positive_infinity), 'Inf', 'hex conversion preserves infinity');
is(sprintf('%o', $negative_infinity), '-Inf', 'octal conversion preserves negative infinity');
is(sprintf('%u', $nan), 'NaN', 'unsigned conversion preserves NaN');

done_testing;
