use strict;
use warnings;
use Test::More tests => 2;

my $args = sub { *_{ARRAY} }->(1, 2, 3);
is ref $args, 'ARRAY', 'underscore glob ARRAY slot is an array reference';
is join(' ', @$args), '1 2 3', 'underscore glob ARRAY slot exposes this call arguments';
