use strict;
use warnings;
use Test::More tests => 1;

sub Pack::age::l3 {
    my @frame = caller(0);
    return $frame[3];
}

is(Pack::age::l3(), 'Pack::age::l3',
    'caller reports the package from a fully qualified sub declaration');
