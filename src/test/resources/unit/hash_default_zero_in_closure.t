use strict;
use warnings;
use Test::More;

my $default = 0;
my $getter = sub {
    if (!exists $_[0]->{value}) {
        $_[0]->{value} = $default;
    }
    $_[0]->{value};
};

is $getter->({}), 0, 'closure default zero is retained through hash assignment';
done_testing;
