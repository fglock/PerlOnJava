#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use 5.38.0;

# Test implementation of a tied hash class
package TiedHash;

sub TIEHASH {
    my ($class, @args) = @_;
    my $self = {
        hash => {},
        args => \@args,
        fetch_count => 0,
        store_count => 0,
        exists_count => 0,
        delete_count => 0,
        firstkey_count => 0,
        nextkey_count => 0,
        scalar_count => 0,
    };
    return bless $self, $class;
}

sub FETCH {
    my ($self, $key) = @_;
    $self->{fetch_count}++;
    return $self->{hash}{$key};
}

sub STORE {
    my ($self, $key, $value) = @_;
    $self->{store_count}++;
    $self->{hash}{$key} = $value;
}

sub EXISTS {
    my ($self, $key) = @_;
    $self->{exists_count}++;
    return exists $self->{hash}{$key};
}

sub DELETE {
    my ($self, $key) = @_;
    $self->{delete_count}++;
    return delete $self->{hash}{$key};
}

sub CLEAR {
    my ($self) = @_;
    %{$self->{hash}} = ();
}

sub FIRSTKEY {
    my ($self) = @_;
    $self->{firstkey_count}++;
    my $a = keys %{$self->{hash}}; # reset iterator
    return each %{$self->{hash}};
}

sub NEXTKEY {
    my ($self, $lastkey) = @_;
    $self->{nextkey_count}++;
    return each %{$self->{hash}};
}

sub SCALAR {
    my ($self) = @_;
    $self->{scalar_count}++;
    return scalar %{$self->{hash}};
}

sub DESTROY {
    my ($self) = @_;
    # Could set a flag to verify DESTROY was called
}

# Test class that tracks method calls
package TrackedTiedHash;
our @ISA = ('TiedHash');
our @method_calls;

sub TIEHASH {
    my ($class, @args) = @_;
    push @method_calls, ['TIEHASH', @args];
    return $class->SUPER::TIEHASH(@args);
}

sub FETCH {
    my ($self, $key) = @_;
    push @method_calls, ['FETCH', $key];
    return $self->SUPER::FETCH($key);
}

sub STORE {
    my ($self, $key, $value) = @_;
    push @method_calls, ['STORE', $key, $value];
    return $self->SUPER::STORE($key, $value);
}

sub EXISTS {
    my ($self, $key) = @_;
    push @method_calls, ['EXISTS', $key];
    return $self->SUPER::EXISTS($key);
}

sub DELETE {
    my ($self, $key) = @_;
    push @method_calls, ['DELETE', $key];
    return $self->SUPER::DELETE($key);
}

sub CLEAR {
    my ($self) = @_;
    push @method_calls, ['CLEAR'];
    return $self->SUPER::CLEAR();
}

sub FIRSTKEY {
    my ($self) = @_;
    push @method_calls, ['FIRSTKEY'];
    return $self->SUPER::FIRSTKEY();
}

sub NEXTKEY {
    my ($self, $lastkey) = @_;
    push @method_calls, ['NEXTKEY', $lastkey];
    return $self->SUPER::NEXTKEY($lastkey);
}

sub SCALAR {
    my ($self) = @_;
    push @method_calls, ['SCALAR'];
    return $self->SUPER::SCALAR();
}

sub DESTROY {
    my ($self) = @_;
    push @method_calls, ['DESTROY'];
    return $self->SUPER::DESTROY() if $self->can('SUPER::DESTROY');
}

# Main test package
package main;

subtest 'Basic tie operations' => sub {
    my %hash;

    # Test tie with no arguments
    my $obj = tie %hash, 'TiedHash';
    ok(defined $obj, 'tie returns object');
    isa_ok($obj, 'TiedHash', 'returned object has correct class');

    # Test tied() function
    my $tied_obj = tied %hash;
    is($tied_obj, $obj, 'tied() returns the same object');

    # Test untie
    my $untie_result = untie %hash;
    ok($untie_result, 'untie returns true');

    # Verify hash is no longer tied
    is(tied %hash, undef, 'tied() returns undef after untie');
};

subtest 'Tie with arguments' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash', 'arg1', 'arg2', 42;

    is_deeply($obj->{args}, ['arg1', 'arg2', 42], 'arguments passed to TIEHASH');
};

subtest 'FETCH and STORE operations' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Store values
    $hash{key1} = 'value1';
    $hash{key2} = 'value2';
    $hash{key3} = 'value3';

    is($obj->{store_count}, 3, 'STORE called three times');

    # Fetch values
    is($hash{key1}, 'value1', 'first value fetched correctly');
    is($hash{key2}, 'value2', 'second value fetched correctly');
    is($hash{key3}, 'value3', 'third value fetched correctly');

    is($obj->{fetch_count}, 3, 'FETCH called three times');

    # Fetch undefined key
    is($hash{nonexistent}, undef, 'undefined key returns undef');
};

subtest 'EXISTS and DELETE operations' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Set up test data
    $hash{exists} = 'yes';
    $hash{also} = 'exists';

    # Test EXISTS
    ok(exists $hash{exists}, 'key "exists" exists');
    ok(!exists $hash{nothere}, 'key "nothere" does not exist');
    ok(exists $hash{also}, 'key "also" exists');

    is($obj->{exists_count}, 3, 'EXISTS called three times');

    # Test DELETE
    my $deleted = delete $hash{exists};
    is($deleted, 'yes', 'DELETE returns the deleted value');
    ok(!exists $hash{exists}, 'key no longer exists after delete');

    is($obj->{delete_count}, 1, 'DELETE called once');
};

subtest 'CLEAR operation' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Add elements
    %hash = (a => 1, b => 2, c => 3);
    is(scalar keys %hash, 3, 'hash has 3 elements');

    # Clear hash
    %hash = ();
    is(scalar keys %hash, 0, 'hash is empty after clear');
    is_deeply($obj->{hash}, {}, 'internal hash is empty');
};

subtest 'FIRSTKEY and NEXTKEY operations' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Add elements
    %hash = (a => 1, b => 2, c => 3);

    # Iterate through keys
    my @keys_collected;
    my @values_collected;

    foreach my $key (keys %hash) {
        push @keys_collected, $key;
        push @values_collected, $hash{$key};
    }

    is(scalar @keys_collected, 3, 'collected 3 keys');
    is_deeply([sort @keys_collected], ['a', 'b', 'c'], 'all keys collected');

    ok($obj->{firstkey_count} >= 1, 'FIRSTKEY called');
    ok($obj->{nextkey_count} >= 2, 'NEXTKEY called');

    # Test each
    my %copy;
    while (my ($k, $v) = each %hash) {
        $copy{$k} = $v;
    }
    is_deeply(\%copy, {a => 1, b => 2, c => 3}, 'each() works correctly');
};

subtest 'Hash assignment' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    # Assign list
    %hash = (x => 1, y => 2, z => 3);
    is($hash{x}, 1, 'x => 1');
    is($hash{y}, 2, 'y => 2');
    is($hash{z}, 3, 'z => 3');

    # Assign from another hash
    my %source = (a => 'A', b => 'B');
    %hash = %source;
    is_deeply({%hash}, {a => 'A', b => 'B'}, 'hash assignment works');

    # Assign empty list
    %hash = ();
    is(scalar keys %hash, 0, 'empty list assignment clears hash');
};

subtest 'Hash slices' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    %hash = (a => 1, b => 2, c => 3, d => 4, e => 5);

    # Read slice
    my @slice = @hash{'b', 'd'};
    is_deeply(\@slice, [2, 4], 'hash slice read works');

    # Write slice
    @hash{'b', 'd'} = ('B', 'D');
    is($hash{b}, 'B', 'slice assignment to key b');
    is($hash{d}, 'D', 'slice assignment to key d');

    # Slice with non-existent keys
    @hash{'x', 'y', 'z'} = (24, 25, 26);
    is($hash{x}, 24, 'slice creates new key x');
    is($hash{y}, 25, 'slice creates new key y');
    is($hash{z}, 26, 'slice creates new key z');
};

subtest 'Hash in different contexts' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    %hash = (a => 1, b => 2, c => 3);

    # Scalar context
    my $scalar = %hash;
    is($scalar, 3, 'hash in scalar context');

    # Boolean context
    ok(%hash, 'non-empty hash is true in boolean context');

    %hash = ();
    ok(!%hash, 'empty hash is false in boolean context');

    # List context
    %hash = (a => 1, b => 2);
    my @list = %hash;
    is(scalar @list, 4, 'hash in list context returns all keys and values');
};

subtest 'Method call tracking' => sub {
    @TrackedTiedHash::method_calls = ();  # Clear method calls

    my %hash;
    tie %hash, 'TrackedTiedHash', 'init_arg';

    # Verify TIEHASH was called
    is($TrackedTiedHash::method_calls[0][0], 'TIEHASH', 'TIEHASH called');
    is($TrackedTiedHash::method_calls[0][1], 'init_arg', 'TIEHASH received argument');

    # Clear for next tests
    @TrackedTiedHash::method_calls = ();

    # Test various operations
    $hash{test} = 'value';
    is($TrackedTiedHash::method_calls[0][0], 'STORE', 'STORE called');
    is($TrackedTiedHash::method_calls[0][1], 'test', 'STORE key');
    is($TrackedTiedHash::method_calls[0][2], 'value', 'STORE value');

    @TrackedTiedHash::method_calls = ();
    my $val = $hash{test};
    is($TrackedTiedHash::method_calls[0][0], 'FETCH', 'FETCH called');
    is($TrackedTiedHash::method_calls[0][1], 'test', 'FETCH key');

    @TrackedTiedHash::method_calls = ();
    my $exists = exists $hash{test};
    is($TrackedTiedHash::method_calls[0][0], 'EXISTS', 'EXISTS called');
    is($TrackedTiedHash::method_calls[0][1], 'test', 'EXISTS key');

    @TrackedTiedHash::method_calls = ();
    delete $hash{test};
    is($TrackedTiedHash::method_calls[0][0], 'DELETE', 'DELETE called');
    is($TrackedTiedHash::method_calls[0][1], 'test', 'DELETE key');
};

subtest 'Multiple tied hashes' => sub {
    my (%hash1, %hash2);
    my $obj1 = tie %hash1, 'TiedHash';
    my $obj2 = tie %hash2, 'TiedHash';

    # Verify they are independent
    %hash1 = (first => 'hash');
    %hash2 = (second => 'hash');

    is($hash1{first}, 'hash', 'first hash has correct value');
    is($hash2{second}, 'hash', 'second hash has correct value');
    isnt($obj1, $obj2, 'separate objects created');
};

subtest 'Retying a hash' => sub {
    my %hash = (a => 1, b => 2);

    # First tie
    my $obj1 = tie %hash, 'TiedHash';
    %hash = (x => 10, y => 20);
    is($hash{x}, 10, 'first tie works');

    # Retie without untie (should replace)
    my $obj2 = tie %hash, 'TiedHash';
    is(scalar keys %hash, 0, 'retie creates new empty hash');
    isnt($obj1, $obj2, 'new object created on retie');
};

subtest 'Foreach loops with tied hashes' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    %hash = (a => 1, b => 2, c => 3, d => 4, e => 5);

    # Foreach loop reading keys
    my @collected_keys;
    foreach my $key (keys %hash) {
        push @collected_keys, $key;
    }
    is(scalar @collected_keys, 5, 'foreach collected all keys');
    is_deeply([sort @collected_keys], ['a', 'b', 'c', 'd', 'e'], 'all keys present');

    # Foreach loop reading values
    my @collected_values;
    foreach my $value (values %hash) {
        push @collected_values, $value;
    }
    is(scalar @collected_values, 5, 'foreach collected all values');
    is_deeply([sort @collected_values], [1, 2, 3, 4, 5], 'all values present');

    # Foreach modifying values
    foreach my $key (keys %hash) {
        $hash{$key} *= 2;
    }
    is_deeply({%hash}, {a => 2, b => 4, c => 6, d => 8, e => 10}, 'foreach can modify values');

    # Check method counts
    ok($obj->{fetch_count} > 0, 'FETCH called during foreach');
    ok($obj->{store_count} > 0, 'STORE called during foreach modification');
};

subtest 'Hash operations with undef' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    # Store undef
    $hash{undef_value} = undef;
    $hash{defined_value} = 'defined';
    $hash{another_undef} = undef;

    is($hash{undef_value}, undef, 'can store undef value');
    ok(exists $hash{undef_value}, 'key with undef value exists');

    is($hash{defined_value}, 'defined', 'defined value retrieved correctly');
    is($hash{another_undef}, undef, 'another undef value stored');

    # Key undef (should stringify to empty string)
    $hash{''} = 'empty key';
    is($hash{''}, 'empty key', 'empty string key works');

    # Verify we can delete undef values
    delete $hash{undef_value};
    ok(!exists $hash{undef_value}, 'can delete key with undef value');
};

subtest 'References to tied hashes' => sub {
    my %hash;
    tie %hash, 'TiedHash';
    %hash = (a => 1, b => 2, c => 3);

    my $ref = \%hash;
    is(ref $ref, 'HASH', 'reference to tied hash is HASH ref');

    # Access through reference
    is($ref->{a}, 1, 'can access elements through reference');

    # Modify through reference
    $ref->{b} = 20;
    is($hash{b}, 20, 'modification through reference works');

    # Add new key through reference
    $ref->{d} = 4;
    is($hash{d}, 4, 'adding key through reference works');

    # Delete through reference
    delete $ref->{a};
    ok(!exists $hash{a}, 'delete through reference works');
};

subtest 'Local and tied hashes' => sub {
    our %hash;
    tie %hash, 'TiedHash';
    %hash = (original => 'value');

    {
        local %hash = (localized => 'new');
        is($hash{localized}, 'new', 'local value set correctly');
        ok(!exists $hash{original}, 'original key not in localized hash');
    }

    # Note: behavior with local and tie can be complex
    # The exact behavior may depend on the Perl implementation
};

subtest 'DESTROY called on untie' => sub {
    # Test with TrackedTiedHash to verify DESTROY is called
    {
        @TrackedTiedHash::method_calls = ();  # Clear method calls

        my %hash;
        tie %hash, 'TrackedTiedHash';
        %hash = (test => 'value');

        # Clear method calls before untie
        @TrackedTiedHash::method_calls = ();

        # Untie should trigger DESTROY
        untie %hash;

        # Check that DESTROY was called
        my $destroy_called = 0;
        for my $call (@TrackedTiedHash::method_calls) {
            if (ref($call) eq 'ARRAY' && $call->[0] eq 'DESTROY') {
                $destroy_called = 1;
                last;
            }
        }
        ok($destroy_called, 'DESTROY called on untie');
    }

    # Test with a class that doesn't implement DESTROY
    {
        package NoDestroyTiedHash;

        sub TIEHASH {
            my ($class) = @_;
            return bless {}, $class;
        }

        sub FETCH { return "dummy" }
        sub STORE { }

        package main;

        my %hash;
        tie %hash, 'NoDestroyTiedHash';

        # This should not throw an error even though DESTROY doesn't exist
        eval { untie %hash; };
        ok(!$@, 'untie works even when DESTROY is not implemented');
    }
};

subtest 'SCALAR method' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Empty hash
    is(scalar %hash, '0', 'scalar on empty hash returns "0"');
    is($obj->{scalar_count}, 1, 'SCALAR called once');

    # Non-empty hash
    %hash = (a => 1, b => 2, c => 3);
    my $scalar = scalar %hash;
    is($scalar, 3, 'scalar on non-empty hash');
    is($obj->{scalar_count}, 2, 'SCALAR called twice');

    # Boolean context also calls SCALAR
    if (%hash) {
        pass('hash evaluates to true');
    }
    ok($obj->{scalar_count} >= 3, 'SCALAR called in boolean context');
};

subtest 'Special keys' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    # Numeric keys
    $hash{42} = 'numeric';
    is($hash{42}, 'numeric', 'numeric key works');
    is($hash{'42'}, 'numeric', 'numeric key stringified works');

    # Keys with special characters
    $hash{'key with spaces'} = 'spaced';
    is($hash{'key with spaces'}, 'spaced', 'key with spaces works');

    $hash{"key\nwith\nnewlines"} = 'multiline';
    is($hash{"key\nwith\nnewlines"}, 'multiline', 'key with newlines works');

    $hash{'key:with:colons'} = 'colons';
    is($hash{'key:with:colons'}, 'colons', 'key with colons works');

    # Very long key
    my $long_key = 'x' x 1000;
    $hash{$long_key} = 'long';
    is($hash{$long_key}, 'long', 'very long key works');

    # Unicode key
    $hash{'ключ'} = 'unicode';
    is($hash{'ключ'}, 'unicode', 'unicode key works');
};

subtest 'Nested data structures' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    # Store references
    $hash{array} = [1, 2, 3];
    $hash{hash} = {a => 1, b => 2};
    $hash{scalar} = \'scalar ref';

    is(ref $hash{array}, 'ARRAY', 'can store array reference');
    is_deeply($hash{array}, [1, 2, 3], 'array reference content correct');

    is(ref $hash{hash}, 'HASH', 'can store hash reference');
    is_deeply($hash{hash}, {a => 1, b => 2}, 'hash reference content correct');

    is(ref $hash{scalar}, 'SCALAR', 'can store scalar reference');
    is(${$hash{scalar}}, 'scalar ref', 'scalar reference content correct');

    # Modify through references
    push @{$hash{array}}, 4;
    is_deeply($hash{array}, [1, 2, 3, 4], 'can modify array through reference');

    $hash{hash}{c} = 3;
    is_deeply($hash{hash}, {a => 1, b => 2, c => 3}, 'can modify hash through reference');
};

subtest 'Copy operations' => sub {
    my %hash;
    tie %hash, 'TiedHash';
    %hash = (a => 1, b => 2, c => 3);

    # Copy to regular hash
    my %copy = %hash;
    is_deeply(\%copy, {a => 1, b => 2, c => 3}, 'copy to regular hash works');
    ok(!tied %copy, 'copy is not tied');

    # Copy from regular hash
    my %source = (x => 10, y => 20);
    %hash = %source;
    is_deeply({%hash}, {x => 10, y => 20}, 'copy from regular hash works');

    # Copy between tied hashes
    my %hash2;
    tie %hash2, 'TiedHash';
    %hash2 = %hash;
    is_deeply({%hash2}, {x => 10, y => 20}, 'copy between tied hashes works');
};

subtest 'Error handling' => sub {
    # Test with a broken tied hash implementation
    {
        package BrokenTiedHash;

        sub TIEHASH { bless {}, shift }
        sub FETCH { die "FETCH died" }
        sub STORE { die "STORE died" }
        sub EXISTS { die "EXISTS died" }

        package main;

        my %hash;
        tie %hash, 'BrokenTiedHash';

        # Test error handling
        eval { $hash{key} };
        like($@, qr/FETCH died/, 'FETCH error propagated');

        eval { $hash{key} = 'value' };
        like($@, qr/STORE died/, 'STORE error propagated');

        eval { exists $hash{key} };
        like($@, qr/EXISTS died/, 'EXISTS error propagated');
    }
};

subtest 'Performance characteristics' => sub {
    my %hash;
    my $obj = tie %hash, 'TiedHash';

    # Add many elements
    for my $i (1..100) {
        $hash{"key$i"} = "value$i";
    }

    is(scalar keys %hash, 100, 'added 100 elements');
    is($obj->{store_count}, 100, 'STORE called 100 times');

    # Access all elements
    $obj->{fetch_count} = 0;  # Reset counter
    my $sum = 0;
    for my $i (1..100) {
        $sum++ if $hash{"key$i"};
    }
    is($sum, 100, 'accessed all 100 elements');
    is($obj->{fetch_count}, 100, 'FETCH called 100 times');

    # Clear and verify
    %hash = ();
    is(scalar keys %hash, 0, 'hash cleared');
};

subtest 'Integration with Perl built-ins' => sub {
    my %hash;
    tie %hash, 'TiedHash';
    %hash = (a => 1, b => 2, c => 3, d => 4, e => 5);

    # grep on keys
    my @selected = grep { $_ =~ /[ace]/ } keys %hash;
    is(scalar @selected, 3, 'grep on keys works');

    # map on values
    my @doubled = map { $_ * 2 } values %hash;
    is(scalar @doubled, 5, 'map on values works');
    is_deeply([sort { $a <=> $b } @doubled], [2, 4, 6, 8, 10], 'values doubled correctly');

    # sort keys
    my @sorted = sort keys %hash;
    is_deeply(\@sorted, ['a', 'b', 'c', 'd', 'e'], 'sort keys works');

    # reverse hash
    my %reversed = reverse %hash;
    is($reversed{1}, 'a', 'reverse hash works');
    is($reversed{2}, 'b', 'reverse hash works');
};

subtest 'Edge cases' => sub {
    my %hash;
    tie %hash, 'TiedHash';

    # Empty string key
    $hash{''} = 'empty';
    is($hash{''}, 'empty', 'empty string key works');
    ok(exists $hash{''}, 'empty string key exists');

    # Key that looks like a reference
    $hash{'HASH(0x12345)'} = 'fake ref';
    is($hash{'HASH(0x12345)'}, 'fake ref', 'key that looks like reference works');

    # Numeric zero key
    $hash{0} = 'zero';
    is($hash{0}, 'zero', 'numeric zero key works');
    is($hash{'0'}, 'zero', 'string zero key works');

    # Keys with internal NUL bytes
    $hash{"key\0with\0nuls"} = 'nul bytes';
    is($hash{"key\0with\0nuls"}, 'nul bytes', 'key with NUL bytes works');

    # Self-referential structures
    my $self_ref = {};
    $self_ref->{self} = $self_ref;
    $hash{circular} = $self_ref;
    is(ref $hash{circular}, 'HASH', 'can store circular reference');
    is($hash{circular}{self}, $hash{circular}, 'circular reference preserved');
};

done_testing();
