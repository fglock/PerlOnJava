use strict;
use warnings;
use Test::More tests => 1;

eval { mutate_argument(20000); 1 };
my $error = $@;

like($error, qr/Modification of a read-only value attempted/,
    'an out-of-cache integer literal remains read-only when passed as an argument');

sub mutate_argument {
    $_[0] = 1;
}
