use strict;
use warnings;
use Test::More tests => 4;

my $anonymous = sub {
    use bigfloat;
    return 1;
};

sub named {
    use bigfloat;
    return 2;
}

my $outside_anonymous = 3;
my $outside_named = 4;

isa_ok($anonymous->(), 'Math::BigFloat', 'bigfloat applies inside anonymous sub');
is(ref($outside_anonymous), '', 'anonymous sub pragma does not leak');
isa_ok(named(), 'Math::BigFloat', 'bigfloat applies inside named sub');
is(ref($outside_named), '', 'named sub pragma does not leak');
