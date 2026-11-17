use strict;
use warnings;
use Test::More;

my %hash = %{; { b => 5, answer => 42 }};
is($hash{b}, 5, 'semicolon-disambiguated block dereference assigns hash entries');
is($hash{answer}, 42, 'block dereference preserves the complete anonymous hash');

done_testing();
