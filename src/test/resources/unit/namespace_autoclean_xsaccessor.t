#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

{
    package NamespaceAutocleanXSAccessor::Direct;
    use namespace::autoclean;
    use Class::XSAccessor accessors => { value => 'value' };
}

ok(NamespaceAutocleanXSAccessor::Direct->can('value'),
    'namespace::autoclean keeps Class::XSAccessor-generated accessors');

my $direct = bless { value => 42 }, 'NamespaceAutocleanXSAccessor::Direct';
is($direct->value, 42, 'generated accessor still works after autoclean');

SKIP: {
    skip 'Moo is not installed', 1 unless eval { require Moo; 1 };

    {
    package NamespaceAutocleanXSAccessor::MooClass;
    Moo->import;
    namespace::autoclean->import;
    has('name' => (is => 'ro'));
    }

    my $moo = NamespaceAutocleanXSAccessor::MooClass->new(name => 'kept');
    is($moo->name, 'kept', 'Moo accessors generated through Class::XSAccessor survive autoclean');
}

done_testing;
