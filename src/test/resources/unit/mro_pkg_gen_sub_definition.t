use strict;
use warnings;
use Test::More tests => 3;
use mro ();

{
    package MroPkgGenSubDefinition;
    our ($before, $after_definition, $after_redefinition);

    BEGIN { $before = mro::get_pkg_gen(__PACKAGE__) }
    sub local_method { 'first' }
    BEGIN { $after_definition = mro::get_pkg_gen(__PACKAGE__) }
    no warnings 'redefine';
    sub local_method { 'second' }
    BEGIN { $after_redefinition = mro::get_pkg_gen(__PACKAGE__) }
}

package main;
isnt(
    $MroPkgGenSubDefinition::after_definition,
    $MroPkgGenSubDefinition::before,
    'defining a named sub changes the package generation',
);
isnt(
    $MroPkgGenSubDefinition::after_redefinition,
    $MroPkgGenSubDefinition::after_definition,
    'redefining a named sub changes the package generation again',
);
is(MroPkgGenSubDefinition->local_method, 'second', 'redefined method remains callable');
