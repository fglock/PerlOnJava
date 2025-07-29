#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Summary of Hash Autovivification Rules in Perl
# 
# **Operations that DO autovivify:**
# - Assignment: `$h->{key} = 'val'`
# - Element access: `$h->{key}` (creates empty hash `$h = {}`, but not the key)
# - keys function: `keys %{$h}`
# - values function: `values %{$h}`
# - each function: `each %{$h}`
# - delete function: `delete $h->{key}`
# - exists function: `exists $h->{a}{b}` (creates parent structures)
# - defined function: `defined $h->{key}` (creates the hash)
# 
# **Operations that do NOT autovivify:**
# - Direct dereference in rvalue context: `my @list = %{$h}`
# - Function return values: `%{get_undef()}`
# - Constant values: `%{undef}`
# 
# **Key principles:**
# 1. Any operation that could potentially modify the data structure will autovivify
# 2. Hash element access always autovivifies the hash itself, even in read-only contexts
# 3. Multi-level access autovivifies all intermediate levels but not the final element
# 4. Pure read operations on the whole hash (not individual elements) do not autovivify
# 
# **For the Text::CSV_PP case:**
# The operation `%{$self->{_CACHE}} = %$ctx` should autovivify both `$self` (to a hashref) and `$self->{_CACHE}` (to a hashref)
# because it's an assignment operation.

# Test autovivification on double deref
subtest 'Hash autovivification in double deref' => sub {
    my $x;
    $x->{a}{a};
    is_deeply($x, { a => {} }, "double deref");

    my $x2;
    $x2->{a}{a} = 3;
    is_deeply($x2, { a => { a => 3 } }, "double deref lvalue");
};
 
# Test autovivification in lvalue contexts (should NOT throw errors)
subtest 'Autovivification in lvalue contexts' => sub {
    my $x;
    
    # Hash assignment
    eval { %{$x->{hash}} = (a => 1, b => 2); };
    is($@, '', 'Hash assignment should autovivify');
    is($x->{hash}{a}, 1, 'Value correctly assigned after autovivification');
    
    # Direct key assignment
    my $y;
    eval { $y->{key} = 'value'; };
    is($@, '', 'Direct key assignment should autovivify');
    is($y->{key}, 'value', 'Value correctly assigned');
    
    # Nested autovivification
    my $z;
    eval { $z->{a}{b}{c} = 'deep'; };
    is($@, '', 'Nested assignment should autovivify');
    is($z->{a}{b}{c}, 'deep', 'Nested value correctly assigned');
    
    # Array autovivification within hash
    my $w;
    eval { push @{$w->{array}}, 'item'; };
    is($@, '', 'Array push should autovivify');
    is($w->{array}[0], 'item', 'Array item correctly assigned');
    
    # Hash slice assignment
    my $v;
    eval { @{$v->{hash}}{qw(x y z)} = (1, 2, 3); };
    is($@, '', 'Hash slice assignment should autovivify');
    is($v->{hash}{y}, 2, 'Hash slice value correctly assigned');
};

# Test cases for reading/dereferencing undefined values - these SHOULD error
subtest 'Reading from undefined values should error' => sub {
    my $x;
    
    # Reading from undefined hash
    eval { my @list = %{$x}; };
    like($@, qr/Can't use an undefined value as an? HASH reference/, 
         'Dereferencing undef variable as hash should error');
    
    # Keys on undefined
    my $y;
    eval { my @keys = keys %{$y}; };
    is($@, '', 'keys on undefined does NOT error (special case)');
    ok(defined $y && ref $y eq 'HASH', 'keys autovivifies to empty hash');
    
    # Values on undefined
    my $z;
    eval { my @vals = values %{$z}; };
    is($@, '', 'values on undefined does NOT error (special case)');
    ok(defined $z && ref $z eq 'HASH', 'values autovivifies to empty hash');
    
    # Each on undefined
    my $w;
    eval { my ($k, $v) = each %{$w}; };
    is($@, '', 'each on undefined does NOT error (special case)');
    ok(defined $w && ref $w eq 'HASH', 'each autovivifies to empty hash');
};

# Test edge cases
subtest 'Edge cases' => sub {
    # Exists should autovivify the parent but not the final key
    my $x;
    eval { 
        if (exists $x->{a}{b}) {
            # Should not execute
        }
    };
    is($@, '', 'exists should not throw error');
    ok(exists $x->{a}, 'Parent should be autovivified by exists');
    ok(!exists $x->{a}{b}, 'Final key should not be autovivified by exists');
    
    # Delete should autovivify
    my $y;
    eval { delete $y->{key}; };
    is($@, '', 'delete should autovivify');
    ok(ref $y eq 'HASH', 'delete created a hashref');
    
    # Defined check DOES autovivify even at single level
    my $z;
    eval { 
        if (defined $z->{key}) {
            # Should not execute
        }
    };
    is($@, '', 'defined check should not error');
    ok(defined $z && ref $z eq 'HASH', 'single-level defined check DOES autovivify the hash');
    ok(!exists $z->{key}, 'but the key itself is not created');
    
    # Multi-level defined also autovivifies parents
    my $a;
    eval {
        if (defined $a->{x}{y}) {
            # Should not execute
        }
    };
    is($@, '', 'deep defined check should not error');
    ok(defined $a && exists $a->{x}, 'defined check autovivifies parent structures');
    ok(!exists $a->{x}{y}, 'but not the final key');
};

# Test the specific Text::CSV_PP case
subtest 'Text::CSV_PP specific case' => sub {
    my $self;
    
    eval { %{$self->{_CACHE}} = (a => 1); };
    is($@, '', 'Text::CSV_PP case: hash assignment to $self->{_CACHE} should autovivify');
    is(ref $self, 'HASH', '$self should be autovivified to hashref');
    is(ref $self->{_CACHE}, 'HASH', '$self->{_CACHE} should be autovivified to hashref');
    is($self->{_CACHE}{a}, 1, 'Value should be correctly assigned');
};

# Test cases that should definitely error
subtest 'Cases that should always error' => sub {
    # Dereferencing non-reference values
    my $scalar = "not a ref";
    eval { my @x = %{$scalar}; };
    like($@, qr/Can't use string .* as an? HASH ref/, 
         'Dereferencing non-reference should error');
    
    # Test with a subroutine that returns undef
    sub return_undef { return undef; }
    
    eval { my @x = %{return_undef()}; };
    like($@, qr/Can't use an undefined value as an? HASH reference/, 
         'Dereferencing function return value of undef should error');
    
    # Test with explicit undef value in a variable
    my $undef_value = undef;
    eval { my @x = %{$undef_value}; };
    like($@, qr/Can't use an undefined value as an? HASH reference/, 
         'Dereferencing variable containing undef should error');
};

# Summary of autovivification rules
subtest 'Autovivification rules summary' => sub {
    # Rule 1: Assignment context always autovivifies
    my $h1;
    eval { $h1->{key} = 'value'; };
    is($@, '', 'Direct assignment autovivifies');
    
    my $h2;
    eval { %{$h2} = (a => 1); };
    is($@, '', 'Hash assignment autovivifies');
    
    # Rule 2: keys/values/each are special - they autovivify
    my $h3;
    eval { keys %{$h3}; };
    is($@, '', 'keys autovivifies');
    ok(defined $h3 && ref $h3 eq 'HASH', 'Hash was created by keys()');
    
    # Rule 3: Simple dereferencing in rvalue context does NOT autovivify
    my $h4;
    eval { my @x = %{$h4}; };
    like($@, qr/Can't use an undefined value as an? HASH reference/, 
         'Simple dereference in rvalue context errors');
    
    # Rule 4: Multi-level access autovivifies intermediate levels
    my $h5;
    eval { $h5->{a}{b}{c} = 'value'; };
    is($@, '', 'Multi-level assignment autovivifies all levels');
    
    my $h6;
    eval { exists $h6->{a}{b}; };
    is($@, '', 'exists autovivifies intermediate levels');
    ok(exists $h6->{a}, 'Intermediate level was created');
    
    # Rule 5: Any hash element access (even single level) autovivifies the hash
    my $h7;
    eval { defined $h7->{key}; };
    is($@, '', 'Hash element access autovivifies');
    ok(defined $h7 && ref $h7 eq 'HASH', 'Hash was created by element access');
};

done_testing();

