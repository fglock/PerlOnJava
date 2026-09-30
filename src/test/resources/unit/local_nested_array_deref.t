use strict;
use warnings;
use Test::More tests => 6;

my @x;
eval { @{local $x[0][0]} = 1; };
like($@, qr/Can't use an undefined value as an ARRAY reference/,
    'localized nested array dereference rejects an undefined intermediate');
ok(!defined $x[0][0], 'the failed local dereference does not define the leaf');

my @y;
eval { @{1, local $y[0][0]} = 1; };
like($@, qr/Can't use an undefined value as an ARRAY reference/,
    'the list-expression variant rejects an undefined intermediate');
ok(!defined $y[0][0], 'the list-expression failure does not define the leaf');

my @defined = ([]);
eval { local $defined[0][0]; };
is($@, '', 'localized nested array dereference accepts an existing array reference');
ok(!defined $defined[0][0], 'localization preserves an existing nested array slot');
