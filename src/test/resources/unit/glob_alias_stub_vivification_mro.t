use strict;
use warnings;
use Test::More;

{
    package GlobAliasStubVivification::Base;
    package GlobAliasStubVivification::Derived;
    our @ISA = ('GlobAliasStubVivification::Base');
}

undef *GlobAliasStubVivification::Base::method;
*GlobAliasStubVivification::Alias::method = *GlobAliasStubVivification::Base::method;
() = \&GlobAliasStubVivification::Alias::method;

eval { GlobAliasStubVivification::Derived->method };
like($@, qr/Undefined subroutine/, 'named reference vivifies the aliased method stub');

done_testing;
