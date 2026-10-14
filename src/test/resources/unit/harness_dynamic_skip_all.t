use strict;
use warnings;

# This is deliberately loaded at runtime: CPAN tests commonly discover an
# optional prerequisite inside eval STRING, then dynamically import
# Test::More's skip_all plan when it is unavailable.
eval q{use Test::More skip_all => 'dynamic optional dependency is unavailable'};

die "execution continued after dynamic Test::More skip_all\n";
