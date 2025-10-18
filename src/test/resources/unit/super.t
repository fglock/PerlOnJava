use strict;
use Test::More tests => 3;
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

use Test::More;
use parent -norequire, 'A';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'B';
}

# Define package C, inheriting from B
package C;

use Test::More;
use parent -norequire, 'B';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'C';
}

# Test the inheritance and SUPER::method functionality
package main;

use Test::More;

my $c = C->new();
my $output = $c->speak();

# Check each part of the output using Test::More
like($output, qr/A/, "A's speak method called");
like($output, qr/B/, "B's speak method called");
like($output, qr/C/, "C's speak method called");

done_testing();
