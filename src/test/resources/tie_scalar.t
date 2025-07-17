#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use 5.38.0;

# Test implementation of a tied scalar class
package TiedScalar;

sub TIESCALAR {
    my ($class, @args) = @_;
    my $self = { 
        value => undef, 
        args => \@args,
        fetch_count => 0,
        store_count => 0,
    };
    return bless $self, $class;
}

sub FETCH {
    my ($self) = @_;
    $self->{fetch_count}++;
    return $self->{value};
}

sub STORE {
    my ($self, $value) = @_;
    $self->{store_count}++;
    $self->{value} = $value;
}

sub DESTROY {
    my ($self) = @_;
    # Could set a flag to verify DESTROY was called
}

# Test class that tracks method calls
package TrackedTiedScalar;
our @ISA = ('TiedScalar');
our @method_calls;

sub TIESCALAR {
    my ($class, @args) = @_;
    push @method_calls, ['TIESCALAR', @args];
    return $class->SUPER::TIESCALAR(@args);
}

sub FETCH {
    my ($self) = @_;
    push @method_calls, ['FETCH'];
    return $self->SUPER::FETCH();
}

sub STORE {
    my ($self, $value) = @_;
    push @method_calls, ['STORE', $value];
    return $self->SUPER::STORE($value);
}

sub DESTROY {
    my ($self) = @_;
    push @method_calls, ['DESTROY'];
    return $self->SUPER::DESTROY() if $self->can('SUPER::DESTROY');
}

sub UNTIE {
    my ($self, $count) = @_;
    push @method_calls, ['UNTIE', $count];
}

# Main test package
package main;

subtest 'Basic tie operations' => sub {
    my $scalar;
    
    # Test tie with no arguments
    my $obj = tie $scalar, 'TiedScalar';
    ok(defined $obj, 'tie returns object');
    isa_ok($obj, 'TiedScalar', 'returned object has correct class');
    
    # Test tied() function
    my $tied_obj = tied $scalar;
    is($tied_obj, $obj, 'tied() returns the same object');
    
    # Test untie
    my $untie_result = untie $scalar;
    ok($untie_result, 'untie returns true');
    
    # Verify scalar is no longer tied
    is(tied $scalar, undef, 'tied() returns undef after untie');
};

subtest 'Tie with arguments' => sub {
    my $scalar;
    my $obj = tie $scalar, 'TiedScalar', 'arg1', 'arg2', 42;
    
    is_deeply($obj->{args}, ['arg1', 'arg2', 42], 'arguments passed to TIESCALAR');
};

subtest 'FETCH operations' => sub {
    my $scalar;
    my $obj = tie $scalar, 'TiedScalar';
    
    # Initial fetch should return undef
    is($scalar, undef, 'initial value is undef');
    is($obj->{fetch_count}, 1, 'FETCH called once');
    
    # Multiple fetches
    my $val1 = $scalar;
    my $val2 = $scalar;
    is($obj->{fetch_count}, 3, 'FETCH called for each access');
    
    # Fetch in different contexts
    if ($scalar) { }  # boolean context
    is($obj->{fetch_count}, 4, 'FETCH called in boolean context');
    
    my $str = "$scalar";  # string context
    is($obj->{fetch_count}, 5, 'FETCH called in string context');
    
    my $num = $scalar + 0;  # numeric context
    is($obj->{fetch_count}, 6, 'FETCH called in numeric context');
};

subtest 'STORE operations' => sub {
    my $scalar;
    my $obj = tie $scalar, 'TiedScalar';
    
    # Store a simple value
    $scalar = 42;
    is($obj->{store_count}, 1, 'STORE called once');
    is($obj->{value}, 42, 'value stored correctly');
    is($scalar, 42, 'FETCH returns stored value');
    
    # Store different types
    $scalar = "hello";
    is($obj->{value}, "hello", 'string stored correctly');
    
    $scalar = [1, 2, 3];
    is_deeply($obj->{value}, [1, 2, 3], 'arrayref stored correctly');
    
    $scalar = { a => 1, b => 2 };
    is_deeply($obj->{value}, { a => 1, b => 2 }, 'hashref stored correctly');
    
    $scalar = undef;
    is($obj->{value}, undef, 'undef stored correctly');
};

subtest 'Operations that trigger FETCH and STORE' => sub {
    my $scalar;
    my $obj = tie $scalar, 'TiedScalar';
    
    # Set initial value
    $scalar = 10;
    my $initial_fetch = $obj->{fetch_count};
    my $initial_store = $obj->{store_count};
    
    # Increment
    $scalar++;
    is($obj->{fetch_count}, $initial_fetch + 1, 'increment triggers FETCH');
    is($obj->{store_count}, $initial_store + 1, 'increment triggers STORE');
    is($scalar, 11, 'increment works correctly');
    
    # Addition assignment
    $scalar += 5;
    is($scalar, 16, 'addition assignment works');
    
    # String concatenation
    $scalar = "Hello";
    $scalar .= " World";
    is($scalar, "Hello World", 'string concatenation works');
};

subtest 'Method call tracking' => sub {
    @TrackedTiedScalar::method_calls = ();  # Clear method calls
    
    my $scalar;
    tie $scalar, 'TrackedTiedScalar', 'init_arg';
    
    # Verify TIESCALAR was called
    is($TrackedTiedScalar::method_calls[0][0], 'TIESCALAR', 'TIESCALAR called');
    is($TrackedTiedScalar::method_calls[0][1], 'init_arg', 'TIESCALAR received argument');
    
    # Trigger FETCH
    my $val = $scalar;
    is($TrackedTiedScalar::method_calls[1][0], 'FETCH', 'FETCH called');
    
    # Trigger STORE
    $scalar = 'new value';
    is($TrackedTiedScalar::method_calls[2][0], 'STORE', 'STORE called');
    is($TrackedTiedScalar::method_calls[2][1], 'new value', 'STORE received correct value');
};

subtest 'Multiple tied scalars' => sub {
    my ($scalar1, $scalar2);
    my $obj1 = tie $scalar1, 'TiedScalar';
    my $obj2 = tie $scalar2, 'TiedScalar';
    
    # Verify they are independent
    $scalar1 = "first";
    $scalar2 = "second";
    
    is($scalar1, "first", 'first scalar has correct value');
    is($scalar2, "second", 'second scalar has correct value');
    isnt($obj1, $obj2, 'separate objects created');
};

subtest 'Retying a scalar' => sub {
    my $scalar = "initial value";
    
    # First tie
    my $obj1 = tie $scalar, 'TiedScalar';
    $scalar = "tied value";
    is($scalar, "tied value", 'first tie works');
    
    # Retie without untie (should replace)
    my $obj2 = tie $scalar, 'TiedScalar';
    is($scalar, undef, 'retie creates new object with undef value');
    isnt($obj1, $obj2, 'new object created on retie');
};

subtest 'Local and tied scalars' => sub {
    our $scalar;
    tie $scalar, 'TiedScalar';
    $scalar = "original";
    
    {
        local $scalar = "localized";
        is($scalar, "localized", 'local value set correctly');
    }
    
    # Note: behavior with local and tie can be complex
    # The exact behavior may depend on the Perl implementation
};

subtest 'References to tied scalars' => sub {
    my $scalar;
    tie $scalar, 'TiedScalar';
    $scalar = "test value";
    
    my $ref = \$scalar;
    is($$ref, "test value", 'dereference works');
    
    $$ref = "new value";
    is($scalar, "new value", 'assignment through reference works');
};

subtest 'DESTROY called on untie' => sub {
    # Test with TrackedTiedScalar to verify DESTROY is called
    {
        @TrackedTiedScalar::method_calls = ();  # Clear method calls

        my $scalar;
        tie $scalar, 'TrackedTiedScalar';  # Don't keep a reference to the tied object
        $scalar = "test value";

        # Verify tie and store were called
        is($TrackedTiedScalar::method_calls[0][0], 'TIESCALAR', 'TIESCALAR called');
        is($TrackedTiedScalar::method_calls[1][0], 'STORE', 'STORE called');

        # Clear method calls before untie
        @TrackedTiedScalar::method_calls = ();

        # Untie should trigger UNTIE then DESTROY
        untie $scalar;

        # Check that both UNTIE and DESTROY were called
        is(scalar(@TrackedTiedScalar::method_calls), 2, 'Two methods called on untie');
        is($TrackedTiedScalar::method_calls[0][0], 'UNTIE', 'UNTIE called first');
        is($TrackedTiedScalar::method_calls[1][0], 'DESTROY', 'DESTROY called second');
    }

    # Test with a class that doesn't implement DESTROY
    {
        package NoDestroyTiedScalar;

        sub TIESCALAR {
            my ($class) = @_;
            return bless {}, $class;
        }

        sub FETCH { return "dummy" }
        sub STORE { }

        package main;

        my $scalar;
        tie $scalar, 'NoDestroyTiedScalar';

        # This should not throw an error even though DESTROY doesn't exist
        eval { untie $scalar; };
        ok(!$@, 'untie works even when DESTROY is not implemented');
    }
};

subtest 'UNTIE called before DESTROY' => sub {
    # Test that UNTIE is called before DESTROY
        @TrackedTiedScalar::method_calls = ();  # Clear method calls

        my $scalar;
        tie $scalar, 'TrackedTiedScalar';
        $scalar = "test value";

        # Clear method calls before untie
        @TrackedTiedScalar::method_calls = ();

        # Untie should trigger UNTIE then DESTROY
        untie $scalar;

        # Check that both methods were called in the correct order
        is(scalar(@TrackedTiedScalar::method_calls), 2, 'Two methods called on untie');
        is($TrackedTiedScalar::method_calls[0][0], 'UNTIE', 'UNTIE called first');
        is($TrackedTiedScalar::method_calls[1][0], 'DESTROY', 'DESTROY called second');
};

done_testing();

