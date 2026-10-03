use strict;
use warnings;
use Test::More;

plan skip_all => 'requires a terminal on STDIN' unless -t STDIN;

ok(-t, '-t without an operand tests STDIN');
is(-t, -t STDIN, 'bare -t agrees with explicit -t STDIN');

done_testing;
