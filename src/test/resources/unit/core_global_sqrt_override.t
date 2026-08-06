use strict;
use warnings;
use Test::More;

BEGIN {
    *CORE::GLOBAL::sqrt = sub { CORE::sqrt(shift) };
}

my $original = *CORE::GLOBAL::sqrt{CODE};
no warnings 'redefine';
*CORE::GLOBAL::sqrt = sub {
    $_[0]++;
    goto &$original;
};

my $value = 99;
is(sqrt($value), 10, 'CORE::GLOBAL::sqrt override receives and can modify arguments');

done_testing;
