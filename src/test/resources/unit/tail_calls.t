use feature 'say';
use feature 'current_sub';  # For __SUB__ support
use strict;
use Test::More;
use warnings;

###################
# goto &NAME tests

# Test 1: Basic tail call
{
    my @calls;
    sub foo { push @calls, "foo(@_)"; if ($_[0] > 0) { unshift @_, $_[0]-1; goto &foo; } }
    foo(2);
    is_deeply(\@calls, ['foo(2)', 'foo(1 2)', 'foo(0 1 2)'], 'goto &NAME with arguments');
}

# Test 2: goto &NAME with return value
{
    sub bar { return 42 if $_[0] == 0; unshift @_, $_[0]-1; goto &bar; }
    is(bar(3), 42, 'goto &NAME returns correct value');
}

###################
# goto __SUB__ tests (requires current_sub feature)

SKIP: {
    eval { require feature; feature->import('current_sub'); 1 } or skip('current_sub feature not available', 2);
    
    # Test 3: Basic tail call with __SUB__
    {
        my @calls2;
        sub baz { 
            push @calls2, "baz(@_)"; 
            if ($_[0] > 0) { 
                unshift @_, $_[0]-1; 
                goto __SUB__; 
            } 
        }
        baz(2);
        is_deeply(\@calls2, ['baz(2)', 'baz(1 2)', 'baz(0 1 2)'], 'goto __SUB__ with arguments');
    }
    
    # Test 4: goto __SUB__ with return value
    {
        sub qux { return 99 if $_[0] == 0; unshift @_, $_[0]-1; goto __SUB__; }
        is(qux(3), 99, 'goto __SUB__ returns correct value');
    }
}

###################
# @_ aliasing across goto &SUB
# Regression test: goto &SUB must pass the caller's @_ aliased to the
# target sub's @_, so that `$_[N] = ...` in the target sub mutates the
# caller's variable. Previously the bytecode emitted
# `argsValue.getList().getArrayOfAlias()`, and RuntimeArray.getList()
# deep-copied each element via `new RuntimeScalar(element)` — which
# silently broke aliasing across the tail call. JSON::Validator's
# coerce/boolean validators (and many other CPAN modules) depend on it.

# Test 5: goto &NAME preserves $_[0] aliasing to caller's variable
{
    sub mutator_target { $_[0] = "MUTATED" }
    sub mutator_via_goto { goto &mutator_target }
    my $x = "orig";
    mutator_via_goto($x);
    is($x, "MUTATED", 'goto &NAME preserves @_ aliasing (single arg)');
}

# Test 6: aliasing survives nested goto &NAME chains and method dispatch
{
    package GotoAliasObj;
    sub new        { bless {}, shift }
    sub mutate     { $_[1] = "M" }
    sub via_goto1  { goto &GotoAliasObj::via_goto2 }
    sub via_goto2  { my $self = shift; $self->mutate($_[0]) }
    sub entry      { my $self = shift; $self->via_goto1($_[0]) }
    package main;
    my $y = "orig";
    GotoAliasObj->new->entry($y);
    is($y, "M", 'goto &NAME preserves @_ aliasing across method dispatch chain');
}

# Test 7: goto __SUB__ also preserves aliasing
{
    my $hits = 0;
    sub recursive_mutator {
        $hits++;
        if ($hits < 3) { goto __SUB__ }
        $_[0] = "DONE";
    }
    my $z = "orig";
    recursive_mutator($z);
    is($z, "DONE", 'goto __SUB__ preserves @_ aliasing');
}

done_testing();

