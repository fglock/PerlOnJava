use strict;
use warnings;
use feature qw(postderef postderef_qq);
no warnings 'experimental::postderef';
use Test::More tests => 4;

my $scalar = 'value';
my $sref = \$scalar;
is("$sref->$*", "$scalar", 'scalar postderef interpolates');

my $aref = [5, 20, 0];
is("$aref->@*", '5 20 0', 'array postderef interpolates');
is("$aref->@[0,2]", '5 0', 'array postderef slice interpolates');

my $href = { red => 1, blue => 2 };
is("$href->@{qw(red blue)}", '1 2', 'hash postderef slice interpolates');
