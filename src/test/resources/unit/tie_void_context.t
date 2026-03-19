#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test for GitHub issue #20: tied variable in void context doesn't call FETCH
# https://github.com/fglock/PerlOnJava/issues/20
#
# When accessing a tied hash/array element in void context, PerlOnJava incorrectly
# optimizes out the variable access, preventing the tied object's FETCH method
# from being called. This breaks expected Perl semantics where tied variable
# access should always trigger the appropriate tie methods regardless of context.

# Test implementation that tracks method calls
package TrackingTiedHash;

our @method_calls;

sub TIEHASH {
    my ($class) = @_;
    @method_calls = ();
    return bless {}, $class;
}

sub FETCH {
    my ($self, $key) = @_;
    push @method_calls, ['FETCH', $key];
    return "value_$key";
}

sub STORE {
    my ($self, $key, $value) = @_;
    push @method_calls, ['STORE', $key, $value];
}

sub EXISTS {
    my ($self, $key) = @_;
    push @method_calls, ['EXISTS', $key];
    return 1;
}

sub DELETE {
    my ($self, $key) = @_;
    push @method_calls, ['DELETE', $key];
}

sub CLEAR {
    my ($self) = @_;
    push @method_calls, ['CLEAR'];
}

sub FIRSTKEY { return undef; }
sub NEXTKEY { return undef; }
sub SCALAR { return 0; }

# Tied array tests
package TrackingTiedArray;

our @array_method_calls;

sub TIEARRAY {
    my ($class) = @_;
    @array_method_calls = ();
    return bless [], $class;
}

sub FETCH {
    my ($self, $index) = @_;
    push @array_method_calls, ['FETCH', $index];
    return "value_$index";
}

sub STORE {
    my ($self, $index, $value) = @_;
    push @array_method_calls, ['STORE', $index, $value];
}

sub FETCHSIZE { return 10; }
sub STORESIZE { }
sub EXISTS { return 1; }

package main;

subtest 'Tied hash FETCH in void context - basic' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    # This is the core issue - accessing a tied hash in void context
    # should still call FETCH
    $hash{key};
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 1, 'FETCH called once in void context');
    is($fetches[0][1], 'key', 'FETCH called with correct key');
};

# NOTE: eval { } blocks are not tested here because PerlOnJava transforms
# `eval { BLOCK }` into `sub { BLOCK }->(@_)` at parse time, which means
# the block becomes a subroutine whose return value is always captured,
# effectively making the last statement run in scalar context instead of void context.
# This is a known limitation - see OperatorParser.java line 81-84.

subtest 'Tied hash FETCH in scalar context (control test)' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    # This should definitely call FETCH (scalar context)
    my $val = $hash{key};
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 1, 'FETCH called in scalar context');
    is($val, 'value_key', 'Value returned correctly');
};

subtest 'Tied hash FETCH in list context (control test)' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    # This should definitely call FETCH (list context)
    my @val = ($hash{key});
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 1, 'FETCH called in list context');
};

subtest 'Tied hash FETCH multiple void accesses' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    # Multiple void context accesses should each call FETCH
    $hash{key1};
    $hash{key2};
    $hash{key3};
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 3, 'FETCH called three times for three void accesses');
    is($fetches[0][1], 'key1', 'First FETCH with correct key');
    is($fetches[1][1], 'key2', 'Second FETCH with correct key');
    is($fetches[2][1], 'key3', 'Third FETCH with correct key');
};

subtest 'Tied array FETCH in void context' => sub {
    my @array;
    tie @array, 'TrackingTiedArray';
    @TrackingTiedArray::array_method_calls = ();
    
    # Void context access on tied array
    $array[0];
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedArray::array_method_calls;
    is(scalar @fetches, 1, 'Array FETCH called in void context');
    is($fetches[0][1], 0, 'Array FETCH called with correct index');
};

# NOTE: Tied scalar access in void context ($scalar;) is optimized out by standard Perl too.
# This is correct behavior - only hash/array element access needs to call FETCH in void context
# because the subscript expression might have side effects.
# See: "Useless use of private variable in void context" warning in Perl.

subtest 'Tied hash FETCH in statement modifier context' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    # Void context in for loop (statement modifier)
    $hash{$_} for qw(a b c);
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 3, 'FETCH called for each iteration in statement modifier');
};

subtest 'Tied hash FETCH via variable key in void context' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    my $key = 'dynamic_key';
    $hash{$key};
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 1, 'FETCH called with variable key in void context');
    is($fetches[0][1], 'dynamic_key', 'FETCH received correct dynamic key');
};

subtest 'Tied hash FETCH with complex expression key in void context' => sub {
    my %hash;
    tie %hash, 'TrackingTiedHash';
    @TrackingTiedHash::method_calls = ();
    
    my $base = 'key';
    $hash{$base . '_suffix'};
    
    my @fetches = grep { $_->[0] eq 'FETCH' } @TrackingTiedHash::method_calls;
    is(scalar @fetches, 1, 'FETCH called with expression key in void context');
    is($fetches[0][1], 'key_suffix', 'FETCH received correctly computed key');
};

done_testing();
