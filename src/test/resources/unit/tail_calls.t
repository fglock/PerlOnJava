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

done_testing();

