use strict;
use warnings;
use Test::More;

{
    package DirectArgumentCopyBorrowedCleanup::Object;
    sub DESTROY { ++$main::direct_argument_copy_destroyed }
}

sub inspect_argument {
    my ($value) = @_;
    return ref $value;
}

our $direct_argument_copy_destroyed = 0;
my $object = bless {}, 'DirectArgumentCopyBorrowedCleanup::Object';

is(
    inspect_argument($object),
    'DirectArgumentCopyBorrowedCleanup::Object',
    'immediate argument copy observes the object',
);
is(
    $direct_argument_copy_destroyed,
    0,
    'callee scope exit does not destroy the caller argument',
);
is(
    ref $object,
    'DirectArgumentCopyBorrowedCleanup::Object',
    'caller retains its object after the proven copy body returns',
);

undef $object;
is($direct_argument_copy_destroyed, 1, 'caller release retains normal DESTROY timing');

done_testing;
