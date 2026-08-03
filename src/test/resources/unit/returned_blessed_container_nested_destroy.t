use strict;
use warnings;

use Test::More tests => 2;

our $destroyed = 0;

{
    package NestedDestroyProbe;
    sub DESTROY { ++$main::destroyed }

    package ReturnedContainerProbe;
    sub make {
        my $nested = bless {}, 'NestedDestroyProbe';
        return bless [$nested], __PACKAGE__;
    }
}

my $outer = ReturnedContainerProbe::make();
is($destroyed, 0, 'nested object survives while returned container is alive');
undef $outer;
is($destroyed, 1, 'releasing returned container destroys its nested object');
