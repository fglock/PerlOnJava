use strict;
use feature 'say';

# Define package A
package A;

sub new {
    return bless {}, shift;
}

sub speak {
    my $self = shift;
    return 'A';
}

# Define package B, inheriting from A
package B;

use parent -norequire, 'A';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'B';
}

# Define package C, inheriting from B
package C;

use parent -norequire, 'B';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'C';
}

# Test the inheritance and SUPER::method functionality
package main;

# Plan the number of tests
say "1..3";

my $c = C->new();
my $output = $c->speak();

# Check each part of the output
say $output =~ /A/ ? "ok 1 - A's speak method called" : "not ok 1 - A's speak method not called";
say $output =~ /B/ ? "ok 2 - B's speak method called" : "not ok 2 - B's speak method not called";
say $output =~ /C/ ? "ok 3 - C's speak method called" : "not ok 3 - C's speak method not called";

