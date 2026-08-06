use strict;
use warnings;
use Carp qw(confess);
use Test::More;

sub assert_like_carp_assert {
    confess "Assertion failed!" unless $_[0];
}

eval { assert_like_carp_assert(0) };
like(
    $@,
    qr/assert_like_carp_assert.*called at/,
    'Carp::confess keeps the named caller frame',
);

done_testing;
