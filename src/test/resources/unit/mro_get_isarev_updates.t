use strict;
use warnings;
use Test::More;
use mro;

{
    package IsarevBase;
    our $VERSION = 1;
}

is_deeply(
    mro::get_isarev('IsarevBase'),
    [],
    'initial reverse inheritance lookup is empty',
);

eval q{
    package IsarevChild;
    use base qw(IsarevBase);
    1;
} or die $@;

is_deeply(
    mro::get_isarev('IsarevBase'),
    ['IsarevChild'],
    'reverse inheritance lookup sees a class added after the cache was read',
);

eval q{
    package IsarevGrandchild;
    use parent -norequire, qw(IsarevChild);
    1;
} or die $@;

is_deeply(
    [sort @{mro::get_isarev('IsarevBase')}],
    [qw(IsarevChild IsarevGrandchild)],
    'reverse inheritance lookup includes newly added indirect subclasses',
);

{
    package IsarevUniversalParent;
    sub marker { 1 }

    package IsarevLinearChild;
    our @ISA = ('IsarevBase');
}

{
    local @UNIVERSAL::ISA = ('IsarevUniversalParent');

    is_deeply(
        mro::get_linear_isa('IsarevLinearChild'),
        [qw(IsarevLinearChild IsarevBase)],
        'implicit UNIVERSAL parents are hidden from another class linearization',
    );
    is_deeply(
        mro::get_linear_isa('UNIVERSAL'),
        [qw(UNIVERSAL IsarevUniversalParent)],
        'UNIVERSAL exposes its own explicit parents',
    );
    ok(
        IsarevLinearChild->can('marker'),
        'UNIVERSAL parent remains available to method lookup',
    );
}

done_testing;
