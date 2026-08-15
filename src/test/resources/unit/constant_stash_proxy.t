use strict;
use warnings;
use Scalar::Util qw(reftype);
use Test::More tests => 6;

{
    package ConstantStashProxy;
    use constant {
        NUMBER => 3,
        ARRAY  => [qw(a b c)],
    };
}

for my $name (qw(NUMBER ARRAY)) {
    no strict 'refs';
    my $stash = \%ConstantStashProxy::;
    my $entry = \$stash->{$name};

    ok(reftype(${$entry}) eq ($name eq 'ARRAY' ? 'REF' : 'SCALAR'),
        "$name is exposed as a proxy constant in the stash");
    ok(defined &{"ConstantStashProxy::$name"},
        "$name remains available through the CODE slot");
}

is(ConstantStashProxy::NUMBER(), 3, 'scalar proxy constant remains callable');
is_deeply(ConstantStashProxy::ARRAY(), [qw(a b c)],
    'reference proxy constant remains callable');
