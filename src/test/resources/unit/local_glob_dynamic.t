use strict;
use warnings;
use Test::More tests => 10;

# Test local *{$name} - dynamic glob localization

# Test 1: Basic dynamic glob localization
{
    no strict 'refs';
    my $name = "TEST_VAR";
    our $TEST_VAR = "original";
    
    {
        local *{$name};
        $TEST_VAR = "modified";
        is($TEST_VAR, "modified", "Variable modified inside local block");
    }
    
    is($TEST_VAR, "original", "Variable restored after local block");
}

# Test 2: Dynamic glob localization with symbolic dereference
{
    no strict 'refs';
    my $name = "TEST_VAR2";
    ${$name} = "original";
    
    {
        local *{$name};
        ${$name} = "modified";
        is(${$name}, "modified", "Symbolic deref modified inside local block");
    }
    
    is(${$name}, "original", "Symbolic deref restored after local block");
}

# Test 3: Return value from subroutine with local *{$name} (via intermediate variable)
{
    no strict 'refs';
    no warnings;
    
    sub test_return_with_local {
        my $name = "RET_VAR";
        local *{$name};
        ${$name} = "hello";
        my $val = ${$name};
        return $val;
    }
    
    my $result = test_return_with_local();
    is($result, "hello", "Return value captured correctly with local glob");
}

# Test 4: Nested dynamic glob localization
{
    no strict 'refs';
    my $name = "NESTED_VAR";
    ${$name} = "level0";
    
    {
        local *{$name};
        ${$name} = "level1";
        
        {
            local *{$name};
            ${$name} = "level2";
            is(${$name}, "level2", "Nested local glob - inner value");
        }
        
        is(${$name}, "level1", "Nested local glob - middle value restored");
    }
    
    is(${$name}, "level0", "Nested local glob - outer value restored");
}

# Test 5: Direct return of symbolic deref (tests return value cloning)
# This ensures return values are copied before local scope teardown
{
    no strict 'refs';
    no warnings;
    
    sub test_direct_return {
        my $name = "DIRECT_VAR";
        local *{$name};
        ${$name} = "direct_value";
        return ${$name};  # Direct return without intermediate variable
    }
    
    my $result = test_direct_return();
    is($result, "direct_value", "Direct return of symbolic deref with local glob");
}

# Test 6: Local glob in package block (parse_version pattern)
{
    no strict 'refs';
    no warnings;
    
    sub test_package_local {
        my $name = "VERSION";
        {
            package TestPkg;
            no strict 'refs';
            local *{$name};
            ${$name} = "1.23";
            my $v = ${$name};
            return $v;
        }
    }
    
    my $result = test_package_local();
    is($result, "1.23", "Local glob in package block with captured return");
}
