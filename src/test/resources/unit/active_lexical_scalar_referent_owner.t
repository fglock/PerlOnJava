#!/usr/bin/env perl
use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

{
    package ActiveLexicalScalarObject;
    our $destroy_count = 0;
    sub DESTROY { ++$destroy_count }
}

my $weak;
{
    my $cell = 42;
    my $object = bless \$cell, 'ActiveLexicalScalarObject';
    $weak = $object;
    weaken($weak);

    undef $object;

    ok(defined($weak), 'the live lexical scalar cell keeps its object alive');
    is($$weak, 42, 'the weak reference still reaches the lexical scalar value');
    is($ActiveLexicalScalarObject::destroy_count, 0,
        'DESTROY waits while the lexical scalar referent is in scope');
}

ok(!defined($weak), 'leaving the lexical scope releases the scalar cell');
is($ActiveLexicalScalarObject::destroy_count, 1,
    'the scalar referent is destroyed once after its lexical owner leaves');

our $tailcall_weak;
sub active_lexical_scalar_target {
    ok(defined($tailcall_weak), 'the tail-call argument survives into its target');
    is($$tailcall_weak, 7, 'the target can still read the scalar referent');
}

sub active_lexical_scalar_trampoline {
    my $cell = 7;
    my $object = bless \$cell, 'ActiveLexicalScalarObject';
    $tailcall_weak = $object;
    weaken($tailcall_weak);
    unshift @_, $object;
    goto &active_lexical_scalar_target;
}

active_lexical_scalar_trampoline();
ok(!defined($tailcall_weak), 'the tail-call target releases the final scalar cell owner');
is($ActiveLexicalScalarObject::destroy_count, 2,
    'the tail-called scalar object is destroyed once after its target returns');

done_testing;
