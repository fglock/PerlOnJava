#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

{
    package RoleTinyForwardDeclaration;
    sub required_method;

    ::ok(exists &required_method, 'forward-declared sub has a CODE slot');
    ::ok(!defined &required_method, 'forward-declared sub is not defined');
    ::ok(RoleTinyForwardDeclaration->can('required_method'), 'forward-declared sub is visible to can');
}

my $stub_ref = \&RoleTinyForwardDeclaration::required_method;
ok(defined $stub_ref, 'CODE reference to a forward declaration is a defined scalar');
ok(!defined &RoleTinyForwardDeclaration::required_method, 'taking a CODE reference does not define the stub');

{
    package RoleTinyCopiedStub;
}

*RoleTinyCopiedStub::required_method = $stub_ref;
ok(exists &RoleTinyCopiedStub::required_method, 'assigning a stub CODE reference creates a CODE slot');
ok(!defined &RoleTinyCopiedStub::required_method, 'assigning a stub CODE reference preserves undefined stub state');

{
    package RoleTinyDynamicLookup;
    sub imported { 1 }
}

{
    package RoleTinyDynamicLookup;
    no strict 'refs';

    my @defined = grep defined &$_, keys %RoleTinyDynamicLookup::;
    my @exists = grep exists &$_, keys %RoleTinyDynamicLookup::;

    ::ok(grep($_ eq 'imported', @defined), 'defined &$_ resolves in the current package');
    ::ok(grep($_ eq 'imported', @exists), 'exists &$_ resolves in the current package');
}

{
    package RoleTinyLateOverload;

    sub new {
        return bless {}, shift;
    }

    sub as_string {
        return 'welp';
    }

    sub as_num {
        return 219;
    }
}

my $late = RoleTinyLateOverload->new;

{
    package RoleTinyLateOverload;
    require overload;

    overload->import(
        '""' => \&as_string,
        '0+' => 'as_num',
        bool => sub { 0 },
        fallback => 1,
    );
}

is("$late", 'welp', 'late-installed stringify overload applies to existing objects');
is(sprintf('%d', $late), '219', 'late-installed numify overload applies to existing objects');
ok(!$late, 'late-installed bool overload applies to existing objects');

{
    my %methods = (
        method => 'from role',
        keep   => 'still here',
    );

    {
        local @methods{qw(method)};
        delete @methods{qw(method)};
        ok(!exists $methods{method}, 'localized hash slice key can be deleted inside scope');
    }

    is($methods{method}, 'from role', 'localized hash slice restores a deleted key');
    is(join(',', sort keys %methods), 'keep,method', 'localized hash slice restore keeps hash keys intact');
}

done_testing;
