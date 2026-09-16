use strict;
use warnings;
use Test::More;

{
    package LocalGlobAliasMethodRestore::Base;
    sub method { 'base' }

    package LocalGlobAliasMethodRestore::Derived;
    our @ISA = ('LocalGlobAliasMethodRestore::Base');

    package LocalGlobAliasMethodRestore;
    sub replacement { 'replacement' }
}

{
    no warnings 'redefine';
    local *LocalGlobAliasMethodRestore::Base::method =
        *LocalGlobAliasMethodRestore::replacement;
    is(LocalGlobAliasMethodRestore::Derived->method, 'replacement',
        'localized whole-glob alias supplies the method inside its scope');
}

eval 'package LocalGlobAliasMethodRestore::Base; sub method { "redefined" }';
is($@, '', 'named redefinition after the localized alias compiles');
is(LocalGlobAliasMethodRestore::Derived->method, 'redefined',
    'named redefinition does not retain the expired glob alias');

done_testing;
