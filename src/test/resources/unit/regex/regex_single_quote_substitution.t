use strict;
use warnings;
use Test::More;

my $value = "__TYPE__";
$value =~ s'__TYPE__'$type'g;
is($value, '$type', "single-quoted substitution replacement is literal under strict");

done_testing();
