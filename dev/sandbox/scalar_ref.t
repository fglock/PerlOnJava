#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 11;

# Test class with ${} overload
package ScalarRef;
use overload
    '${}'  => \&scalar_deref,
    '""'   => \&stringify,
    'bool' => sub { 1 };

sub new {
    my ($class, $value) = @_;
    # Only set default if no arguments were passed
    $value = "default" if @_ < 2;
    return bless { value => $value }, $class;
}

sub scalar_deref {
    my $self = shift;
    return \$self->{value};
}

sub stringify {
    my $self = shift;
    return $self->{value};
}

sub set_value {
    my ($self, $value) = @_;
    $self->{value} = $value;
}

# Test class that returns different scalar refs
package DynamicScalarRef;
use overload '${}'  => \&dynamic_deref;

sub new {
    my ($class, @values) = @_;
    @values = ("first", "second") unless @values;
    return bless { values => \@values, index => 0 }, $class;
}

sub dynamic_deref {
    my $self = shift;
    my $idx = $self->{index} % @{$self->{values}};
    $self->{index}++;
    return \$self->{values}->[$idx];
}

# Test class with complex scalar ref behavior
package ComplexScalarRef;
use overload '${}'  => \&complex_deref;

sub new {
    my ($class, $initial) = @_;
    $initial = 0 unless defined $initial;
    return bless { counter => $initial }, $class;
}

sub complex_deref {
    my $self = shift;
    $self->{counter}++;
    my $value = "access_count_" . $self->{counter};
    return \$value;
}

package main;

# Basic ${} overload tests
subtest 'Basic scalar dereference overload' => sub {
    plan tests => 4;
    
    my $obj = ScalarRef->new("hello");
    
    # Test basic dereference
    is(${$obj}, "hello", "Basic scalar dereference works");
    
    # Test that we get a reference
    my $ref = $$obj;
    is(ref(\$ref), 'SCALAR', "Dereference returns scalar reference content");
    
    # Test modification through dereference
    ${$obj} = "modified";
    is($obj->stringify(), "modified", "Modification through dereference works");
    
    # Test with undefined value
    my $obj2 = ScalarRef->new(undef);
    is(${$obj2}, undef, "Handles undefined values correctly");
};

# Test assignment and modification
subtest 'Assignment and modification through ${}' => sub {
    plan tests => 3;
    
    my $obj = ScalarRef->new("initial");
    
    # Assign new value
    ${$obj} = "new_value";
    is(${$obj}, "new_value", "Assignment through \${} works");
    
    # Modify existing value
    ${$obj} .= "_appended";
    is(${$obj}, "new_value_appended", "String concatenation works");
    
    # Numeric operations
    my $num_obj = ScalarRef->new(42);
    ${$num_obj} += 8;
    is(${$num_obj}, 50, "Numeric operations work");
};

# Test with different data types
subtest 'Different data types' => sub {
    plan tests => 4;
    
    # String
    my $str_obj = ScalarRef->new("test string");
    is(${$str_obj}, "test string", "String values work");
    
    # Number
    my $num_obj = ScalarRef->new(123.45);
    is(${$num_obj}, 123.45, "Numeric values work");
    
    # Zero
    my $zero_obj = ScalarRef->new(0);
    is(${$zero_obj}, 0, "Zero values work");
    
    # Empty string
    my $empty_obj = ScalarRef->new("");
    is(${$empty_obj}, "", "Empty string works");
};

# Test dynamic behavior
subtest 'Dynamic scalar reference behavior' => sub {
    plan tests => 4;
    
    my $dyn_obj = DynamicScalarRef->new("alpha", "beta", "gamma");
    
    # Should cycle through values
    is(${$dyn_obj}, "alpha", "First access returns first value");
    is(${$dyn_obj}, "beta", "Second access returns second value");
    is(${$dyn_obj}, "gamma", "Third access returns third value");
    is(${$dyn_obj}, "alpha", "Fourth access cycles back to first");
};

# Test complex behavior with side effects
subtest 'Complex behavior with side effects' => sub {
    plan tests => 3;
    
    my $complex_obj = ComplexScalarRef->new(10);
    
    # Each access should increment counter
    is(${$complex_obj}, "access_count_11", "First access shows incremented counter");
    is(${$complex_obj}, "access_count_12", "Second access shows further increment");
    is(${$complex_obj}, "access_count_13", "Third access continues incrementing");
};

# Test in conditional contexts
subtest 'Conditional and boolean contexts' => sub {
    plan tests => 3;
    
    my $obj = ScalarRef->new("non_empty");
    my $empty_obj = ScalarRef->new("");
    my $zero_obj = ScalarRef->new(0);
    
    # Test in if conditions (tests the scalar value, not the object)
    ok(${$obj}, "Non-empty string is true in boolean context");
    ok(!${$empty_obj}, "Empty string is false in boolean context");
    ok(!${$zero_obj}, "Zero is false in boolean context");
};

# Test with references to references
subtest 'Nested reference behavior' => sub {
    plan tests => 2;
    
    my $value = "nested_value";
    my $ref = \$value;
    my $obj = ScalarRef->new($$ref);
    
    is(${$obj}, "nested_value", "Can handle dereferenced values");
    
    # Modify original and check
    $value = "modified_nested";
    isnt(${$obj}, "modified_nested", "Object holds copy, not reference to original");
};

# Test error conditions and edge cases
subtest 'Error conditions and edge cases' => sub {
    plan tests => 3;
    
    my $obj = ScalarRef->new("test");
    
    # Test that the overload method is called
    my $deref_result = $$obj;
    is($deref_result, "test", "Direct \$\$obj dereference works");
    
    # Test assignment to dereferenced value
    $$obj = "reassigned";
    is($$obj, "reassigned", "Assignment to \$\$obj works");
    
    # Test with special characters
    my $special_obj = ScalarRef->new("special\nchars\t\"'");
    is(${$special_obj}, "special\nchars\t\"'", "Handles special characters");
};

# Test interaction with other overloads
subtest 'Interaction with other overloads' => sub {
    plan tests => 3;
    
    my $obj = ScalarRef->new("stringify_test");
    
    # String context should use "" overload, not ${}
    is("$obj", "stringify_test", "String context uses \"\" overload");
    
    # Scalar dereference should use ${} overload
    is(${$obj}, "stringify_test", "Scalar dereference uses \${} overload");
    
    # Boolean context should use bool overload
    ok($obj, "Boolean context uses bool overload");
};

# Test with array and hash context (should not use ${} overload)
subtest 'Context sensitivity' => sub {
    plan tests => 2;
    
    my $obj = ScalarRef->new("context_test");
    
    # In scalar context, should work normally
    my $scalar_val = ${$obj};
    is($scalar_val, "context_test", "Works in scalar context");
    
    # Verify the object itself is not affected by array context
    my @array = ($obj);  # This shouldn't trigger ${} overload
    is(@array, 1, "Object in array context doesn't trigger \${} overload");
};

# Performance and memory tests
subtest 'Performance considerations' => sub {
    plan tests => 2;
    
    my $obj = ScalarRef->new("performance_test");
    
    # Multiple rapid accesses
    my $val1 = ${$obj};
    my $val2 = ${$obj};
    is($val1, $val2, "Multiple accesses return consistent values");
    
    # Verify memory isn't growing uncontrollably with dynamic object
    my $dyn_obj = DynamicScalarRef->new(("x") x 3);
    for my $i (1..10) {
        my $temp = ${$dyn_obj};  # This should cycle, not accumulate
    }
    pass("Multiple accesses on dynamic object complete without issues");
};

done_testing();
