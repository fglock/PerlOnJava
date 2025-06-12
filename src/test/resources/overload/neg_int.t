#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 12;

# Define a test class that overloads neg and int operators
package TestNumber;
use overload
    'neg' => \&negate,
    'int' => \&to_int,
    '""'  => \&stringify;  # For easier testing output

sub new {
    my ($class, $value) = @_;
    return bless { value => $value }, $class;
}

sub negate {
    my $self = shift;
    return TestNumber->new(-$self->{value});
}

sub to_int {
    my $self = shift;
    return int($self->{value});
}

sub stringify {
    my $self = shift;
    return $self->{value};
}

sub value {
    my $self = shift;
    return $self->{value};
}

package main;

# Test the neg operator overload
my $pos = TestNumber->new(42);
my $neg_result = -$pos;

isa_ok($neg_result, 'TestNumber', 'neg returns TestNumber object');
is($neg_result->value, -42, 'neg correctly negates positive number');

my $neg = TestNumber->new(-15);
my $pos_result = -$neg;

isa_ok($pos_result, 'TestNumber', 'neg on negative returns TestNumber object');
is($pos_result->value, 15, 'neg correctly negates negative number');

# Test the int operator overload
my $float = TestNumber->new(3.14159);
my $int_result = int($float);

is($int_result, 3, 'int truncates positive float correctly');

my $neg_float = TestNumber->new(-2.71828);
my $neg_int_result = int($neg_float);

is($neg_int_result, -2, 'int truncates negative float correctly');

my $whole = TestNumber->new(42);
my $whole_int = int($whole);

is($whole_int, 42, 'int preserves whole numbers');

my $zero = TestNumber->new(0.9);
my $zero_int = int($zero);

is($zero_int, 0, 'int truncates 0.9 to 0');

my $neg_zero = TestNumber->new(-0.1);
my $neg_zero_int = int($neg_zero);

is($neg_zero_int, 0, 'int truncates -0.1 to 0');

# Test chaining neg and int operations
my $num = TestNumber->new(-3.7);
my $result = int(-$num);

is($result, 3, 'int(-(-3.7)) = 3');

my $pos2 = TestNumber->new(5.9);
my $chain_result = -int($pos2);

is($chain_result, -5, '-(int(5.9)) = -5');

# Test double negation
my $double_neg = -(-$pos2);
is($double_neg->value, 5.9, 'double negation returns original value');
