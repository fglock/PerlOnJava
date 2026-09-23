use strict;
use warnings;
use Test::More;

{
    package LegacyMethod;
    sub method { shift; join ',', 'method', @_ }

    package main;
    is(method LegacyMethod ('a', 'b'), 'method,a,b',
       'method Package (LIST) remains an indirect method call');
    is((method LegacyMethod 'c', 'd'), 'method,c,d',
       'parenthesis-free indirect method call remains supported');
}

{
    package ForwardStubParent;
    sub AUTOLOAD {
        our $AUTOLOAD;
        return "autoloaded $AUTOLOAD";
    }

    package ForwardStubChild;
    our @ISA = ('ForwardStubParent');
    sub missing;

    package main;
    is(ForwardStubChild->missing, 'autoloaded ForwardStubChild::missing',
       'a declared method stub uses an inherited AUTOLOAD');
}

{
    package BracedSuperParent;
    sub values { shift; join ' ', @_ }

    package BracedSuperChild;
    our @ISA = ('BracedSuperParent');
    my @args = (bless([], 'BracedSuperChild'), 'one');

    my $braced = SUPER::values {@args};
    ::is($braced, 'one', 'SUPER method accepts braced list arguments');
    my $empty = SUPER::values {} @args;
    ::is($empty, 'one', 'empty braced SUPER arguments preserve following args');
    my $trailing = SUPER::values {@args} 'two';
    ::is($trailing, 'one two', 'braced SUPER arguments accept trailing args');
}

{
    is('abc'->CORE::uc, 'ABC', 'qualified CORE method call materializes its wrapper');
    like(eval { ''->missing; 1 } ? '' : $@,
         qr/without a package or object reference/,
         'empty string method receiver has the package-or-object diagnostic');
    like(eval { new {}; 1 } ? '' : $@,
         qr/without a package or object reference/,
         'new with an empty hash receiver has the package-or-object diagnostic');
}

{
    sub main::::root_namespace_method { 'root namespace' }
    is('::'->root_namespace_method, 'root namespace',
       'the root package spelling resolves main:::: methods');
}

BEGIN {
    *LocalPopOverride::pop = sub { $main::pop_override_result = $_[0][0] };
}
{
    package LocalPopOverride;
    sub values { ['overridden'] }

    ::is(pop(LocalPopOverride->values()), 'overridden',
         'parenthesized package-local pop override is called');
    pop LocalPopOverride->values();
    ::is($main::pop_override_result, 'overridden',
         'parenthesis-free package-local pop override is called');
}

done_testing;
