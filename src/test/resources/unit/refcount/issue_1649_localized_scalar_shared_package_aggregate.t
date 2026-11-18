use strict;
use warnings;
use Test::More;

our $destroyed = 0;
our @package_elements;
our %package_values;
our $localized_reference;

{
    package Repro::LocalizedScalarSharedPackageAggregate;
    sub DESTROY { ++$main::destroyed }
}

@package_elements = (bless {}, 'Repro::LocalizedScalarSharedPackageAggregate');
{
    local $localized_reference = \@package_elements;
}

is($destroyed, 0,
    'restoring a localized scalar does not release a package aggregate still in use');
is(scalar @package_elements, 1,
    'the package aggregate retains its element after the localized scalar is restored');

@package_elements = ();
is($destroyed, 1,
    'the package aggregate releases its element when its own slot is cleared');

$package_values{kept} = bless {}, 'Repro::LocalizedScalarSharedPackageAggregate';
{
    local $localized_reference = \%package_values;
}
is($destroyed, 1,
    'restoring a localized scalar does not release a package hash still in use');
is(scalar keys %package_values, 1,
    'the package hash retains its element after the localized scalar is restored');

%package_values = ();
is($destroyed, 2,
    'the package hash releases its element when its own slot is cleared');

{
    local $localized_reference = [
        bless {}, 'Repro::LocalizedScalarSharedPackageAggregate'
    ];
}
is($destroyed, 3,
    'a localized scalar releases the elements of its final anonymous aggregate owner');

done_testing;
