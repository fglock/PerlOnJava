use strict;
use warnings;

use Test::More tests => 3;

our $destroyed = 0;

{
    package ReassignedNestedProbe;
    use overload '""' => sub { 'probe' }, fallback => 1;
    sub DESTROY { ++$main::destroyed }

    package ReassignedOuterProbe;
    sub make {
        my $nested = bless {}, 'ReassignedNestedProbe';
        return bless [$nested], __PACKAGE__;
    }

    sub stringify_argument {
        my $path = shift;
        $path = "$path";
        return $path;
    }
}

my $outer = ReassignedOuterProbe::make();
is(ReassignedOuterProbe::stringify_argument($outer->[0]), 'probe',
    'lexical reference can be replaced by its stringification');
is($destroyed, 0, 'container still owns the nested object');
undef $outer;
is($destroyed, 1, 'lexical replacement did not leave a stale object owner');
