#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Summary of Array Autovivification Rules in Perl
# 
# **Operations that DO autovivify:**
# - Assignment: `$a->[0] = 'val'`
# - Element access: `$a->[0]` (creates empty array `$a = []`, but not the element)
# - push function: `push @{$a}, 'item'`
# - pop function: `pop @{$a}`
# - shift function: `shift @{$a}`
# - unshift function: `unshift @{$a}, 'item'`
# - splice function: `splice @{$a}, 0, 0, 'item'`
# - grep function: `grep { $_ } @{$a}`
# - map function: `map { $_ } @{$a}`
# - foreach loop: `foreach (@{$a}) { }`
# - delete function: `delete $a->[0]`
# - exists function: `exists $a->[0][1]` (creates parent structures)
# - defined function: `defined $a->[0]` (creates the array)
# - Array length read: `$#{$a}`
# - Array length set: `$#{$a} = 10`
# 
# **Operations that do NOT autovivify:**
# - Direct dereference in rvalue context: `my @list = @{$a}`
# - sort function: `sort @{$a}`
# - reverse function: `reverse @{$a}`
# - Scalar context: `my $count = @{$a}`
# - Function return values: `@{get_undef()}`
# - Constant values: `@{undef}`
# 
# **Key principles:**
# 1. Operations that can modify the array structure will autovivify
# 2. Array element access always autovivifies the array itself, even in read-only contexts
# 3. Multi-level access autovivifies all intermediate levels but not the final element
# 4. Non-modifying operations (sort, reverse) do not autovivify
# 5. Operations that work through aliases (grep, map, foreach) autovivify because they can potentially modify
# 
# **Key difference from hashes:**
# Arrays distinguish between modifying operations (push, pop, shift, unshift)
# and non-modifying operations (sort, reverse), while most hash operations autovivify regardless.

# Test autovivification on double deref
subtest 'Array autovivification in double deref' => sub {
    my $x;
    $x->[0][0];
    is_deeply($x, [[]], "double deref");

    my $x2;
    $x2->[0][0] = 3;
    is_deeply($x2, [[3]], "double deref lvalue");
};

# Test autovivification in lvalue contexts (should NOT throw errors)
subtest 'Array autovivification in lvalue contexts' => sub {
    my $x;
    
    # Array assignment
    eval { @{$x->{array}} = (1, 2, 3); };
    is($@, '', 'Array assignment should autovivify');
    is($x->{array}[1], 2, 'Value correctly assigned after autovivification');
    
    # Direct element assignment
    my $y;
    eval { $y->[0] = 'value'; };
    is($@, '', 'Direct element assignment should autovivify');
    is($y->[0], 'value', 'Value correctly assigned');
    
    # Nested autovivification
    my $z;
    eval { $z->[0][1][2] = 'deep'; };
    is($@, '', 'Nested assignment should autovivify');
    is($z->[0][1][2], 'deep', 'Nested value correctly assigned');
    
    # Hash autovivification within array
    my $w;
    eval { $w->[0]{key} = 'value'; };
    is($@, '', 'Hash within array should autovivify');
    is($w->[0]{key}, 'value', 'Hash value correctly assigned');
    
    # Array slice assignment
    my $v;
    eval { @{$v->{array}}[0,2,4] = (1, 3, 5); };
    is($@, '', 'Array slice assignment should autovivify');
    is($v->{array}[2], 3, 'Array slice value correctly assigned');
    
    # Push operation
    my $p;
    eval { push @{$p}, 'item'; };
    is($@, '', 'Push should autovivify');
    is($p->[0], 'item', 'Pushed item correctly assigned');
    
    # Unshift operation
    my $u;
    eval { unshift @{$u->{list}}, 'first'; };
    is($@, '', 'Unshift should autovivify');
    is($u->{list}[0], 'first', 'Unshifted item correctly assigned');
};

# Test cases for reading/dereferencing undefined values
subtest 'Reading from undefined arrays' => sub {
    my $x;
    
    # Reading from undefined array
    eval { my @list = @{$x}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Dereferencing undef variable as array should error');
    
    # But array element access autovivifies
    my $y;
    eval { my $val = $y->[0]; };
    is($@, '', 'Array element access does not error');
    ok(defined $y && ref $y eq 'ARRAY', 'Array was autovivified by element access');
    
    # Scalar context on array
    my $z;
    eval { my $count = @{$z}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Array in scalar context should error');
};

# Test array-specific operations
subtest 'Array-specific operations' => sub {
    # Pop on undefined - autovivifies
    my $p;
    eval { my $val = pop @{$p}; };
    is($@, '', 'Pop autovivifies array');
    ok(defined $p && ref $p eq 'ARRAY', 'Array was created by pop');
    
    # Shift on undefined - autovivifies
    my $s;
    eval { my $val = shift @{$s}; };
    is($@, '', 'Shift autovivifies array');
    ok(defined $s && ref $s eq 'ARRAY', 'Array was created by shift');
    
    # Splice on undefined - autovivifies
    my $sp;
    eval { splice @{$sp}, 0, 0, 'item'; };
    is($@, '', 'Splice autovivifies array');
    is($sp->[0], 'item', 'Splice correctly added item');
    
    # Grep on undefined - autovivifies
    my $g;
    eval { my @result = grep { $_ } @{$g}; };
    is($@, '', 'Grep autovivifies array');
    ok(defined $g && ref $g eq 'ARRAY', 'Array was created by grep');
    
    # Map on undefined - autovivifies
    my $m;
    eval { my @result = map { $_ } @{$m}; };
    is($@, '', 'Map autovivifies array');
    ok(defined $m && ref $m eq 'ARRAY', 'Array was created by map');
    
    # Sort on undefined - does NOT autovivify
    my $so;
    eval { my @result = sort @{$so}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Sort does NOT autovivify array');
    ok(!defined $so, 'Array was NOT created by sort');
    
    # Reverse on undefined - does NOT autovivify
    my $r;
    eval { my @result = reverse @{$r}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Reverse does NOT autovivify array');
    ok(!defined $r, 'Array was NOT created by reverse');
};

# Test edge cases
subtest 'Array edge cases' => sub {
    ## # Exists on array element
    ## my $x;
    ## eval { 
    ##     if (exists $x->[0][1]) {
    ##         # Should not execute
    ##     }
    ## };
    ## is($@, '', 'exists should not throw error');
    ## ok(defined $x && ref $x eq 'ARRAY', 'Array autovivified by exists');
    ## ok(defined $x->[0] && ref $x->[0] eq 'ARRAY', 'Nested array autovivified by exists');
    ## ok(!exists $x->[0][1], 'Final element not created by exists');
    
    ## # Delete on array element
    ## my $y;
    ## eval { delete $y->[5]; };
    ## is($@, '', 'delete should autovivify array');
    ## ok(ref $y eq 'ARRAY', 'delete created an arrayref');
    
    # Defined check on array element
    my $z;
    eval { 
        if (defined $z->[0]) {
            # Should not execute
        }
    };
    is($@, '', 'defined check should not error');
    ok(defined $z && ref $z eq 'ARRAY', 'Array autovivified by defined check');
    
    # Length check
    my $l;
    eval { my $len = $#{$l}; };
    is($@, '', 'Array length check autovivifies');
    ok(defined $l && ref $l eq 'ARRAY', 'Array created by length check');
    
    ## # Setting array length
    ## my $sl;
    ## eval { $#{$sl} = 10; };
    ## is($@, '', 'Setting array length autovivifies');
    ## ok(defined $sl && ref $sl eq 'ARRAY', 'Array created by setting length');
    ## is($#{$sl}, 10, 'Array length correctly set');
};

# Test mixed array/hash autovivification
subtest 'Mixed array/hash autovivification' => sub {
    my $x;
    eval { $x->[0]{key}[1]{foo} = 'bar'; };
    is($@, '', 'Mixed array/hash assignment should autovivify');
    is($x->[0]{key}[1]{foo}, 'bar', 'Value correctly assigned through mixed structure');
    
    my $y;
    eval { push @{$y->{items}}, { name => 'test' }; };
    is($@, '', 'Push to array in hash should autovivify');
    is($y->{items}[0]{name}, 'test', 'Pushed hash correctly stored');
};

# Test cases that should definitely error
subtest 'Cases that should always error' => sub {
    # Dereferencing non-reference values
    my $scalar = "not a ref";
    eval { my @x = @{$scalar}; };
    like($@, qr/Can't use string .* as an? ARRAY ref/, 
         'Dereferencing string as array should error');
    
    # Test with a subroutine that returns undef
    sub return_undef { return undef; }
    
    eval { my @x = @{return_undef()}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Dereferencing function return value of undef should error');
    
    # Test with explicit undef value in a variable
    my $undef_value = undef;
    eval { my @x = @{$undef_value}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Dereferencing variable containing undef should error');
    
    # Wrong reference type
    my $hashref = {};
    eval { my @x = @{$hashref}; };
    like($@, qr/Not an ARRAY reference/, 
         'Dereferencing hash as array should error');
};

# Test foreach loops
subtest 'Foreach loops and array autovivification' => sub {
    # Foreach on undefined array
    my $x;
    eval { 
        foreach my $item (@{$x}) {
            # Should not execute as array is empty
        }
    };
    is($@, '', 'Foreach autovivifies array');
    ok(defined $x && ref $x eq 'ARRAY', 'Array created by foreach');
    
    # Foreach with aliasing
    my $y;
    eval {
        foreach (@{$y->{list}}) {
            $_ = 'modified' if defined $_;
        }
    };
    is($@, '', 'Foreach on nested array autovivifies');
    ok(defined $y->{list} && ref $y->{list} eq 'ARRAY', 'Nested array created');
};

# Summary of array autovivification rules
subtest 'Array autovivification rules summary' => sub {
    # Rule 1: Assignment context always autovivifies
    my $a1;
    eval { $a1->[0] = 'value'; };
    is($@, '', 'Direct assignment autovivifies');
    
    my $a2;
    eval { @{$a2} = (1, 2, 3); };
    is($@, '', 'Array assignment autovivifies');
    
    # Rule 2: Modifying array operations autovivify
    my $a3;
    eval { push @{$a3}, 'item'; };
    is($@, '', 'Push autovivifies');
    
    my $a3b;
    eval { pop @{$a3b}; };
    is($@, '', 'Pop autovivifies');
    
    # Rule 3: Non-modifying operations do NOT autovivify
    my $a4;
    eval { my @sorted = sort @{$a4}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Sort does not autovivify');
    
    my $a4b;
    eval { my @reversed = reverse @{$a4b}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Reverse does not autovivify');
    
    # Rule 4: Simple dereferencing in rvalue context does NOT autovivify
    my $a5;
    eval { my @x = @{$a5}; };
    like($@, qr/Can't use an undefined value as an? ARRAY reference/, 
         'Simple dereference in rvalue context errors');
    
    # Rule 5: Array element access autovivifies the array
    my $a6;
    eval { my $val = $a6->[0]; };
    is($@, '', 'Element access autovivifies');
    ok(defined $a6 && ref $a6 eq 'ARRAY', 'Array was created');
    
    # Rule 6: Array length operations autovivify
    my $a7;
    eval { my $len = $#{$a7}; };
    is($@, '', 'Length check autovivifies');
    ok(defined $a7 && ref $a7 eq 'ARRAY', 'Array was created by length check');
    
    # Rule 7: List-producing operations that can modify autovivify
    my $a8;
    eval { my @items = grep { 1 } @{$a8}; };
    is($@, '', 'Grep autovivifies (can be used with aliases that modify)');
    
    my $a9;
    eval { my @items = map { $_ } @{$a9}; };
    is($@, '', 'Map autovivifies (can be used with aliases that modify)');
};

done_testing();

