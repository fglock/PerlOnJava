use strict;
use warnings;
use Test::More;

my @seen;
my @refs;
for (1 .. 3) {
    push @seen, $_;
    push @refs, \$_;
}

is_deeply \@seen, [1, 2, 3], 'implicit topic receives every streamed range value';
is_deeply [map $$_, @refs], [1, 2, 3], 'references retain distinct range cells';

my @letters;
for ('x' .. 'z') {
    push @letters, $_;
}
is_deeply \@letters, [qw(x y z)], 'string range still streams in order';

done_testing;
