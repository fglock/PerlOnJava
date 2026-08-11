use strict;
use warnings;
use Test::More;

is(sprintf('%.0f', 1.5), '2', 'rounds an odd lower integer up at a tie');
is(sprintf('%.0f', 2.5), '2', 'rounds an even lower integer down at a tie');
is(sprintf('%.0f', -2.5), '-2', 'rounds negative ties to even');
is(sprintf('%#.0f', 2.5), '2.', 'alternate form keeps the decimal point');
is(sprintf('%+06.0f', 2.5), '+00002', 'sign and zero padding follow tie-to-even rounding');
is(sprintf('%.0f', 2400 - 15 * 0.5), '2392',
   'rating calculation at an exact half rounds like Perl');

done_testing;
