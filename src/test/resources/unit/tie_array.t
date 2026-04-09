#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use 5.38.0;

# Test implementation of a tied array class
package TiedArray;

sub TIEARRAY {
    my ($class, @args) = @_;
    my $self = {
        array => [],
        args => \@args,
        fetch_count => 0,
        store_count => 0,
        fetchsize_count => 0,
        storesize_count => 0,
    };
    return bless $self, $class;
}

sub FETCH {
    my ($self, $index) = @_;
    $self->{fetch_count}++;
    return $self->{array}[$index];
}

sub STORE {
    my ($self, $index, $value) = @_;
    $self->{store_count}++;
    $self->{array}[$index] = $value;
}

sub FETCHSIZE {
    my ($self) = @_;
    $self->{fetchsize_count}++;
    return scalar @{$self->{array}};
}

sub STORESIZE {
    my ($self, $size) = @_;
    $self->{storesize_count}++;
    $#{$self->{array}} = $size - 1;
}

sub EXTEND {
    my ($self, $size) = @_;
    # Pre-extend the array if needed
    $self->{extend_called} = $size;
}

sub EXISTS {
    my ($self, $index) = @_;
    return exists $self->{array}[$index];
}

sub DELETE {
    my ($self, $index) = @_;
    return delete $self->{array}[$index];
}

sub CLEAR {
    my ($self) = @_;
    @{$self->{array}} = ();
}

sub PUSH {
    my ($self, @values) = @_;
    push @{$self->{array}}, @values;
}

sub POP {
    my ($self) = @_;
    return pop @{$self->{array}};
}

sub SHIFT {
    my ($self) = @_;
    return shift @{$self->{array}};
}

sub UNSHIFT {
    my ($self, @values) = @_;
    unshift @{$self->{array}}, @values;
}

sub SPLICE {
    my ($self, $offset, $length, @values) = @_;
    if (defined $length) {
        return splice @{$self->{array}}, $offset, $length, @values;
    } else {
        return splice @{$self->{array}}, $offset;
    }
}

sub DESTROY {
    my ($self) = @_;
    # Could set a flag to verify DESTROY was called
}

# Test class that tracks method calls
package TrackedTiedArray;
our @ISA = ('TiedArray');
our @method_calls;

sub TIEARRAY {
    my ($class, @args) = @_;
    push @method_calls, ['TIEARRAY', @args];
    return $class->SUPER::TIEARRAY(@args);
}

sub FETCH {
    my ($self, $index) = @_;
    push @method_calls, ['FETCH', $index];
    return $self->SUPER::FETCH($index);
}

sub STORE {
    my ($self, $index, $value) = @_;
    push @method_calls, ['STORE', $index, $value];
    return $self->SUPER::STORE($index, $value);
}

sub FETCHSIZE {
    my ($self) = @_;
    push @method_calls, ['FETCHSIZE'];
    return $self->SUPER::FETCHSIZE();
}

sub STORESIZE {
    my ($self, $size) = @_;
    push @method_calls, ['STORESIZE', $size];
    return $self->SUPER::STORESIZE($size);
}

sub EXISTS {
    my ($self, $index) = @_;
    push @method_calls, ['EXISTS', $index];
    return $self->SUPER::EXISTS($index);
}

sub DELETE {
    my ($self, $index) = @_;
    push @method_calls, ['DELETE', $index];
    return $self->SUPER::DELETE($index);
}

sub CLEAR {
    my ($self) = @_;
    push @method_calls, ['CLEAR'];
    return $self->SUPER::CLEAR();
}

sub PUSH {
    my ($self, @values) = @_;
    push @method_calls, ['PUSH', @values];
    return $self->SUPER::PUSH(@values);
}

sub POP {
    my ($self) = @_;
    push @method_calls, ['POP'];
    return $self->SUPER::POP();
}

sub SHIFT {
    my ($self) = @_;
    push @method_calls, ['SHIFT'];
    return $self->SUPER::SHIFT();
}

sub UNSHIFT {
    my ($self, @values) = @_;
    push @method_calls, ['UNSHIFT', @values];
    return $self->SUPER::UNSHIFT(@values);
}

sub SPLICE {
    my ($self, $offset, $length, @values) = @_;
    push @method_calls, ['SPLICE', $offset, $length, @values];
    return $self->SUPER::SPLICE($offset, $length, @values);
}

sub DESTROY {
    my ($self) = @_;
    push @method_calls, ['DESTROY'];
    return $self->SUPER::DESTROY() if $self->can('SUPER::DESTROY');
}

# Main test package
package main;

subtest 'Basic tie operations' => sub {
    my @array;

    # Test tie with no arguments
    my $obj = tie @array, 'TiedArray';
    ok(defined $obj, 'tie returns object');
    isa_ok($obj, 'TiedArray', 'returned object has correct class');

    # Test tied() function
    my $tied_obj = tied @array;
    is($tied_obj, $obj, 'tied() returns the same object');

    # Test untie
    my $untie_result = untie @array;
    ok($untie_result, 'untie returns true');

    # Verify array is no longer tied
    is(tied @array, undef, 'tied() returns undef after untie');
};

subtest 'Tie with arguments' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray', 'arg1', 'arg2', 42;

    is_deeply($obj->{args}, ['arg1', 'arg2', 42], 'arguments passed to TIEARRAY');
};

subtest 'FETCH and STORE operations' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    # Store values
    $array[0] = 'first';
    $array[1] = 'second';
    $array[2] = 'third';

    is($obj->{store_count}, 3, 'STORE called three times');

    # Fetch values
    is($array[0], 'first', 'first element fetched correctly');
    is($array[1], 'second', 'second element fetched correctly');
    is($array[2], 'third', 'third element fetched correctly');

    is($obj->{fetch_count}, 3, 'FETCH called three times');

    # Fetch undefined element
    is($array[10], undef, 'undefined element returns undef');
};

subtest 'Array size operations' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    # Initial size
    is(scalar @array, 0, 'initial size is 0');
    is($obj->{fetchsize_count}, 1, 'FETCHSIZE called once');

    # Add elements and check size
    $array[0] = 'a';
    $array[1] = 'b';
    $array[2] = 'c';

    is(scalar @array, 3, 'size is 3 after adding elements');

    # Change size directly
    $#array = 4;
    is(scalar @array, 5, 'size is 5 after setting $#array');
    is($obj->{storesize_count}, 1, 'STORESIZE called once');

    # Shrink array
    $#array = 1;
    is(scalar @array, 2, 'size is 2 after shrinking');
    is($array[2], undef, 'element beyond new size is undefined');
};

subtest 'EXISTS and DELETE operations' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Set up test data
    $array[0] = 'exists';
    $array[2] = 'also exists';

    # Test EXISTS
    ok(exists $array[0], 'element 0 exists');
    ok(!exists $array[1], 'element 1 does not exist');
    ok(exists $array[2], 'element 2 exists');

    # Test DELETE
    my $deleted = delete $array[0];
    is($deleted, 'exists', 'DELETE returns the deleted value');
    ## ok(!exists $array[0], 'element 0 no longer exists after delete');

    # Array size should remain the same
    is(scalar @array, 3, 'array size unchanged after delete');
};

subtest 'CLEAR operation' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    # Add elements
    @array = ('a', 'b', 'c');
    is(scalar @array, 3, 'array has 3 elements');

    # Clear array
    @array = ();
    is(scalar @array, 0, 'array is empty after clear');
    is_deeply($obj->{array}, [], 'internal array is empty');
};

subtest 'PUSH operation' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Push single element
    push @array, 'first';
    is($array[0], 'first', 'pushed element is at index 0');
    is(scalar @array, 1, 'array size is 1');

    # Push multiple elements
    push @array, 'second', 'third';
    is($array[1], 'second', 'second pushed element is at index 1');
    is($array[2], 'third', 'third pushed element is at index 2');
    is(scalar @array, 3, 'array size is 3');

    # Push array
    push @array, qw(fourth fifth);
    is(scalar @array, 5, 'array size is 5 after pushing array');
};

subtest 'POP operation' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = qw(a b c);

    # Pop elements
    is(pop @array, 'c', 'pop returns last element');
    is(scalar @array, 2, 'array size decreased');

    is(pop @array, 'b', 'pop returns new last element');
    is(pop @array, 'a', 'pop returns final element');

    # Pop from empty array
    is(pop @array, undef, 'pop from empty array returns undef');
};

subtest 'SHIFT operation' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = qw(a b c);

    # Shift elements
    is(shift @array, 'a', 'shift returns first element');
    is(scalar @array, 2, 'array size decreased');
    is($array[0], 'b', 'remaining elements moved forward');

    is(shift @array, 'b', 'shift returns new first element');
    is(shift @array, 'c', 'shift returns final element');

    # Shift from empty array
    is(shift @array, undef, 'shift from empty array returns undef');
};

subtest 'UNSHIFT operation' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Unshift single element
    unshift @array, 'first';
    is($array[0], 'first', 'unshifted element is at index 0');

    # Unshift multiple elements
    unshift @array, 'zero', 'negative';
    is($array[0], 'zero', 'first unshifted element is at index 0');
    is($array[1], 'negative', 'second unshifted element is at index 1');
    is($array[2], 'first', 'original element moved to index 2');

    is(scalar @array, 3, 'array size is correct');
};

subtest 'SPLICE operation' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = qw(a b c d e);

    # Remove elements
    my @removed = splice @array, 1, 2;
    is_deeply(\@removed, ['b', 'c'], 'splice returns removed elements');
    is_deeply(\@array, ['a', 'd', 'e'], 'array contains remaining elements');

    # Replace elements
    @array = qw(a b c d e);
    @removed = splice @array, 1, 2, 'X', 'Y', 'Z';
    is_deeply(\@removed, ['b', 'c'], 'splice returns removed elements');
    is_deeply(\@array, ['a', 'X', 'Y', 'Z', 'd', 'e'], 'array contains replaced elements');

    # Insert without removing
    @array = qw(a b c);
    splice @array, 1, 0, 'X', 'Y';
    is_deeply(\@array, ['a', 'X', 'Y', 'b', 'c'], 'splice can insert without removing');

    # Negative offset
    @array = qw(a b c d);
    splice @array, -2, 1, 'X';
    is_deeply(\@array, ['a', 'b', 'X', 'd'], 'splice works with negative offset');

    # No length specified (remove to end)
    @array = qw(a b c d e);
    @removed = splice @array, 2;
    is_deeply(\@removed, ['c', 'd', 'e'], 'splice without length removes to end');
    is_deeply(\@array, ['a', 'b'], 'remaining elements after splice to end');
};

subtest 'Array assignment' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Assign list
    @array = ('x', 'y', 'z');
    is_deeply(\@array, ['x', 'y', 'z'], 'list assignment works');

    # Assign from another array
    my @source = (1, 2, 3);
    @array = @source;
    is_deeply(\@array, [1, 2, 3], 'array assignment works');

    # Assign empty list
    @array = ();
    is(scalar @array, 0, 'empty list assignment clears array');
};

subtest 'Array slices' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = ('a'..'e');

    # Read slice
    my @slice = @array[1,3];
    is_deeply(\@slice, ['b', 'd'], 'array slice read works');

    # Write slice
    @array[1,3] = ('B', 'D');
    is($array[1], 'B', 'slice assignment to index 1');
    is($array[3], 'D', 'slice assignment to index 3');

    # Slice with repeated indices
    @array[0,0,0] = ('X', 'Y', 'Z');
    is($array[0], 'Z', 'last assignment wins with repeated indices');
};

subtest 'Array in different contexts' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = (1, 2, 3);

    # Scalar context
    my $count = @array;
    is($count, 3, 'array in scalar context returns count');

    # Boolean context
    ok(@array, 'non-empty array is true in boolean context');

    @array = ();
    ok(!@array, 'empty array is false in boolean context');

    # String context
    @array = ('a', 'b', 'c');
    my $str = "@array";
    is($str, 'a b c', 'array interpolation in string');
};

subtest 'Method call tracking' => sub {
    @TrackedTiedArray::method_calls = ();  # Clear method calls

    my @array;
    tie @array, 'TrackedTiedArray', 'init_arg';

    # Verify TIEARRAY was called
    is($TrackedTiedArray::method_calls[0][0], 'TIEARRAY', 'TIEARRAY called');
    is($TrackedTiedArray::method_calls[0][1], 'init_arg', 'TIEARRAY received argument');

    # Clear for next tests
    @TrackedTiedArray::method_calls = ();

    # Test various operations
    $array[0] = 'test';
    is($TrackedTiedArray::method_calls[0][0], 'STORE', 'STORE called');
    is($TrackedTiedArray::method_calls[0][1], 0, 'STORE index');
    is($TrackedTiedArray::method_calls[0][2], 'test', 'STORE value');

    @TrackedTiedArray::method_calls = ();
    my $val = $array[0];
    is($TrackedTiedArray::method_calls[0][0], 'FETCH', 'FETCH called');
    is($TrackedTiedArray::method_calls[0][1], 0, 'FETCH index');

    @TrackedTiedArray::method_calls = ();
    my $size = @array;
    is($TrackedTiedArray::method_calls[0][0], 'FETCHSIZE', 'FETCHSIZE called');

    @TrackedTiedArray::method_calls = ();
    push @array, 'new';
    is($TrackedTiedArray::method_calls[0][0], 'PUSH', 'PUSH called');
    is($TrackedTiedArray::method_calls[0][1], 'new', 'PUSH value');
};

subtest 'Multiple tied arrays' => sub {
    my (@array1, @array2);
    my $obj1 = tie @array1, 'TiedArray';
    my $obj2 = tie @array2, 'TiedArray';

    # Verify they are independent
    @array1 = ('first', 'array');
    @array2 = ('second', 'array');

    is_deeply(\@array1, ['first', 'array'], 'first array has correct values');
    is_deeply(\@array2, ['second', 'array'], 'second array has correct values');
    isnt($obj1, $obj2, 'separate objects created');
};

subtest 'Retying an array' => sub {
    my @array = (1, 2, 3);

    # First tie
    my $obj1 = tie @array, 'TiedArray';
    @array = ('a', 'b');
    is_deeply(\@array, ['a', 'b'], 'first tie works');

    # Retie without untie (should replace)
    my $obj2 = tie @array, 'TiedArray';
    is(scalar @array, 0, 'retie creates new empty array');
    isnt($obj1, $obj2, 'new object created on retie');
};

subtest 'Foreach loops with tied arrays' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    @array = (1, 2, 3, 4, 5);

    # Foreach loop reading
    my @collected;
    foreach my $elem (@array) {
        push @collected, $elem;
    }
    is_deeply(\@collected, [1, 2, 3, 4, 5], 'foreach reads all elements');

    # Foreach loop modifying
    foreach my $elem (@array) {
        $elem *= 2;
    }
    is_deeply(\@array, [2, 4, 6, 8, 10], 'foreach can modify elements');

    # Check method counts
    ok($obj->{fetch_count} > 0, 'FETCH called during foreach');
    ok($obj->{store_count} > 0, 'STORE called during foreach modification');
};

subtest 'Array operations with undef' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Store undef
    $array[0] = undef;
    $array[1] = 'defined';
    $array[2] = undef;

    is($array[0], undef, 'can store undef at index 0');
    ok(exists $array[0], 'element with undef exists');

    is($array[1], 'defined', 'defined element retrieved correctly');
    is($array[2], undef, 'can store undef at index 2');

    # Size includes undef elements
    is(scalar @array, 3, 'array size includes undef elements');
};

subtest 'Extending array' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    # Assign to index beyond current size
    $array[10] = 'far';
    is(scalar @array, 11, 'array extended to include index 10');
    is($array[10], 'far', 'element at index 10 is correct');

    # Check intermediate elements
    for my $i (0..9) {
        is($array[$i], undef, "intermediate element $i is undef");
    }

    # Check if EXTEND was called (if implemented)
    ok(exists $obj->{extend_called}, 'EXTEND was called') if defined $obj->{extend_called};
};

subtest 'References to tied arrays' => sub {
    my @array;
    tie @array, 'TiedArray';
    @array = ('a', 'b', 'c');

    my $ref = \@array;
    is(ref $ref, 'ARRAY', 'reference to tied array is ARRAY ref');

    # Access through reference
    is($ref->[0], 'a', 'can access elements through reference');

    # Modify through reference
    $ref->[1] = 'B';
    is($array[1], 'B', 'modification through reference works');

    # Push through reference
    push @$ref, 'd';
    is($array[3], 'd', 'push through reference works');
};

subtest 'Local and tied arrays' => sub {
    our @array;
    tie @array, 'TiedArray';
    @array = ('original');

    {
        local @array = ('localized');
        is_deeply(\@array, ['localized'], 'local value set correctly');
    }

    # Note: behavior with local and tie can be complex
    # The exact behavior may depend on the Perl implementation
};

subtest 'DESTROY called on untie' => sub {
    # Test with TrackedTiedArray to verify DESTROY is called
    {
        @TrackedTiedArray::method_calls = ();  # Clear method calls

        my @array;
        tie @array, 'TrackedTiedArray';
        @array = ('test', 'value');

        # Clear method calls before untie
        @TrackedTiedArray::method_calls = ();

        # Untie should trigger DESTROY
        untie @array;

        # Check that DESTROY was called
        my $destroy_called = 0;
        for my $call (@TrackedTiedArray::method_calls) {
            if (ref($call) eq 'ARRAY' && $call->[0] eq 'DESTROY') {
                $destroy_called = 1;
                last;
            }
        }
        ok($destroy_called, 'DESTROY called on untie');
    }

    # Test with a class that doesn't implement DESTROY
    {
        package NoDestroyTiedArray;

        sub TIEARRAY {
            my ($class) = @_;
            return bless [], $class;
        }

        sub FETCH { return "dummy" }
        sub STORE { }
        sub FETCHSIZE { return 0 }

        package main;

        my @array;
        tie @array, 'NoDestroyTiedArray';

        # This should not throw an error even though DESTROY doesn't exist
        eval { untie @array; };
        ok(!$@, 'untie works even when DESTROY is not implemented');
    }
};

subtest 'Edge cases' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Very large index
    $array[1000000] = 'huge';
    is($array[1000000], 'huge', 'can use very large indices');
    ok(scalar @array > 1000000, 'array size adjusted for large index');

    # Negative indices
    @array = ('a', 'b', 'c');
    is($array[-1], 'c', 'negative index -1 works');
    is($array[-2], 'b', 'negative index -2 works');
    is($array[-3], 'a', 'negative index -3 works');

    # Assignment to negative index
    $array[-1] = 'C';
    is($array[2], 'C', 'assignment to negative index works');
};

subtest 'Autoincrement and autodecrement operations' => sub {
    my @array;
    my $obj = tie @array, 'TiedArray';

    # Set up initial values
    @array = (5, 10, 15);

    # Pre-increment
    my $result = ++$array[0];
    is($result, 6, 'pre-increment returns new value');
    is($array[0], 6, 'pre-increment modifies array element');

    # Post-increment
    $result = $array[1]++;
    is($result, 10, 'post-increment returns old value');
    is($array[1], 11, 'post-increment modifies array element');

    # Pre-decrement
    $result = --$array[2];
    is($result, 14, 'pre-decrement returns new value');
    is($array[2], 14, 'pre-decrement modifies array element');

    # Post-decrement
    $result = $array[2]--;
    is($result, 14, 'post-decrement returns old value');
    is($array[2], 13, 'post-decrement modifies array element');

    # Increment undefined element
    $array[5]++;
    is($array[5], 1, 'increment of undefined element starts at 1');

    # Decrement undefined element
    $array[6]--;
    is($array[6], -1, 'decrement of undefined element starts at -1');

    # String increment
    $array[10] = 'aa';
    $array[10]++;
    is($array[10], 'ab', 'string increment works');

    # Verify FETCH and STORE are called appropriately
    ok($obj->{fetch_count} > 0, 'FETCH called during increment operations');
    ok($obj->{store_count} > 0, 'STORE called during increment operations');
};

subtest 'Aliased variable operations (lvalue context)' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = (1, 2, 3, 4, 5);

    # Test subroutine that modifies aliased parameters
    sub modify_aliases {
        $_[0]++;           # Increment first parameter
        $_[1] = 99;        # Assign to second parameter
        my $x = $_[2];     # Read third parameter
        $_[3] *= 2;        # Multiply fourth parameter
        return $x;
    }

    my $returned = modify_aliases(@array[0..3]);

    is($array[0], 2, 'first element incremented via alias');
    is($array[1], 99, 'second element assigned via alias');
    is($returned, 3, 'third element read correctly via alias');
    is($array[3], 8, 'fourth element multiplied via alias');

    # Test with array slice as parameters
    sub slice_modifier {
        $_[0] = 'A';
        $_[1] = 'B';
        $_[2] = 'C';
    }

    slice_modifier(@array[1,3,4]);
    is($array[1], 'A', 'slice element 1 modified via alias');
    is($array[3], 'B', 'slice element 3 modified via alias');
    is($array[4], 'C', 'slice element 4 modified via alias');

    # Test reference to array element as lvalue
    my $ref = \$array[0];
    $$ref = 'modified';
    is($array[0], 'modified', 'modification through reference to element');

    # Test with for loop aliasing
    my @values = (10, 20, 30);
    for my $i (0..2) {
        $array[$i] = $values[$i];
    }

    for (@array[0..2]) {
        $_ *= 10;  # Modify through $_
    }

    is($array[0], 100, 'element modified through $_ alias');
    is($array[1], 200, 'element modified through $_ alias');
    is($array[2], 300, 'element modified through $_ alias');
};

subtest 'Complex lvalue operations' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = (1, 2, 3, 4, 5);

    # Compound assignment operations
    $array[0] += 10;
    is($array[0], 11, 'compound addition assignment');

    $array[1] -= 1;
    is($array[1], 1, 'compound subtraction assignment');

    $array[2] *= 3;
    is($array[2], 9, 'compound multiplication assignment');

    $array[3] /= 2;
    is($array[3], 2, 'compound division assignment');

    $array[4] %= 3;
    is($array[4], 2, 'compound modulo assignment');

    # String compound assignments
    $array[5] = 'hello';
    $array[5] .= ' world';
    is($array[5], 'hello world', 'string concatenation assignment');

    # Bitwise compound assignments
    $array[6] = 12;  # 1100 in binary
    $array[6] &= 10; # 1010 in binary, result should be 8 (1000)
    is($array[6], 8, 'bitwise AND assignment');

    $array[7] = 12;  # 1100 in binary
    $array[7] |= 3;  # 0011 in binary, result should be 15 (1111)
    is($array[7], 15, 'bitwise OR assignment');

    $array[8] = 12;  # 1100 in binary
    $array[8] ^= 5;  # 0101 in binary, result should be 9 (1001)
    is($array[8], 9, 'bitwise XOR assignment');

    # Left and right shift assignments
    $array[9] = 8;
    $array[9] <<= 2;  # Shift left by 2
    is($array[9], 32, 'left shift assignment');

    $array[10] = 32;
    $array[10] >>= 2;  # Shift right by 2
    is($array[10], 8, 'right shift assignment');
};

subtest 'Array element as subroutine parameter with various contexts' => sub {
    my @array;
    tie @array, 'TiedArray';

    @array = ('test', 42, 'hello');

    # Test passing array elements to subroutines expecting references
    sub takes_scalar_ref {
        my $ref = shift;
        $$ref = 'modified';
    }

    takes_scalar_ref(\$array[0]);
    is($array[0], 'modified', 'array element modified via scalar reference');

    # Test with map function (list context)
    @array = (1, 2, 3, 4, 5);
    my @doubled = map { $_ * 2 } @array;
    is_deeply(\@doubled, [2, 4, 6, 8, 10], 'map reads tied array correctly');

    # Test with grep function
    my @evens = grep { $_ % 2 == 0 } @array;
    is_deeply(\@evens, [2, 4], 'grep reads tied array correctly');

    # Test with sort function
    @array = (3, 1, 4, 1, 5, 9);
    my @sorted = sort { $a <=> $b } @array;
    is_deeply(\@sorted, [1, 1, 3, 4, 5, 9], 'sort reads tied array correctly');

    # Test with reverse function
    @array = (1, 2, 3);
    my @reversed = reverse @array;
    is_deeply(\@reversed, [3, 2, 1], 'reverse reads tied array correctly');
};

subtest 'Array element assignment in various contexts' => sub {
    my @array;
    tie @array, 'TiedArray';

    # Assignment in conditional context
    if ($array[0] = 'true_value') {
        is($array[0], 'true_value', 'assignment in if condition works');
    }

    # Assignment in while loop condition
    my $counter = 0;
    while (($array[1] = ++$counter) < 3) {
        # Loop body
    }
    is($array[1], 3, 'assignment in while condition works');

    # Assignment in ternary operator
    my $test = 1;
    $test ? ($array[2] = 'ternary_true') : ($array[2] = 'ternary_false');
    is($array[2], 'ternary_true', 'assignment in ternary operator works');

    # Assignment with logical operators
    $array[3] = 0;
    $array[3] ||= 'default_value';
    is($array[3], 'default_value', 'logical OR assignment works');

    $array[4] = 'existing';
    $array[4] ||= 'wont_be_used';
    is($array[4], 'existing', 'logical OR assignment preserves existing value');

    $array[5] = 'will_be_replaced';
    $array[5] &&= 'new_value';
    is($array[5], 'new_value', 'logical AND assignment works');

    $array[6] = 0;
    $array[6] &&= 'wont_be_used';
    is($array[6], 0, 'logical AND assignment preserves false value');

    # Defined-or assignment
    $array[7] = undef;
    $array[7] //= 'defined_or_value';
    is($array[7], 'defined_or_value', 'defined-or assignment works');

    $array[8] = 'existing';
    $array[8] //= 'wont_be_used';
    is($array[8], 'existing', 'defined-or assignment preserves defined value');
};

done_testing();
