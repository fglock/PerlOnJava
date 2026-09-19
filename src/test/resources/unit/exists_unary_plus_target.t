use strict;
use warnings;
use Test::More;

my $ref = { nested => { value => 1 } };
ok(exists +($ref // {})->{nested}{value},
    'exists accepts a unary-plus-disambiguated hash element target');

done_testing;
