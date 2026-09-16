use strict;
use warnings;
use Test::More;

{
    package GlobAliasCodeSlotRedefinition::Base;
    sub method { 'base' }

    package GlobAliasCodeSlotRedefinition::Other;
    sub method { 'other' }

    package GlobAliasCodeSlotRedefinition::Alias;
    sub method { 'alias' }

    package GlobAliasCodeSlotRedefinition::Derived;
    our @ISA = ('GlobAliasCodeSlotRedefinition::Base');
}

no warnings 'redefine';
*GlobAliasCodeSlotRedefinition::Base::method =
    *GlobAliasCodeSlotRedefinition::Alias::method;
*GlobAliasCodeSlotRedefinition::Base::method =
    \&GlobAliasCodeSlotRedefinition::Other::method;
undef *GlobAliasCodeSlotRedefinition::Base::method;

eval q{package GlobAliasCodeSlotRedefinition::Base; sub method { 'redefined' }};
is($@, '', 'named redefinition after a CODE-slot replacement compiles');
is(GlobAliasCodeSlotRedefinition::Derived->method, 'redefined',
    'MRO sees the redefined source-package method, not the stale glob alias');

done_testing;
