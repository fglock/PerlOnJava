use strict;
use warnings;
use Test::More tests => 3;

{
    package ConstantMroGeneration;

    our $before;
    BEGIN { $before = mro::get_pkg_gen(__PACKAGE__) }

    use constant ROLE_METHOD => 'constant method';

    package main;

    ok(
        mro::get_pkg_gen('ConstantMroGeneration') > $ConstantMroGeneration::before,
        'installing a constant advances the package method generation',
    );
    ok(
        ConstantMroGeneration->can('ROLE_METHOD'),
        'constant is visible through method lookup',
    );
    is(
        ConstantMroGeneration->ROLE_METHOD,
        'constant method',
        'constant remains callable as a class method',
    );
}
