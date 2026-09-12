use strict;
use warnings;
use Test::More tests => 2;

my @destroyed;
{
    package PlainArrayScopeCleanup;
    sub new { bless [], shift }
    sub DESTROY { push @destroyed, 'destroyed' }
}

{
    my @values = (PlainArrayScopeCleanup->new);
}
is scalar @destroyed, 1, 'array scope exit still releases a directly stored reference';

{
    my @values;
    $values[0] = 42;
    $values[0] = PlainArrayScopeCleanup->new;
}
is scalar @destroyed, 2, 'reference replacement of a primitive array slot retains scope cleanup';
