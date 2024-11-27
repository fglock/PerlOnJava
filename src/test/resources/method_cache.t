use strict;
use feature 'say';

# Define package X
package X;

sub new {
    return bless {}, shift;
}

sub speak {
    my $self = shift;
    return 'X';
}

# Define package Y, with a method of the same name as in X
package Y;

sub new {
    return bless {}, shift;
}

sub speak {
    my $self = shift;
    return 'Y';
}

# Define package Z, with a method of the same name as in X and Y
package Z;

sub new {
    return bless {}, shift;
}

sub speak {
    my $self = shift;
    return 'Z';
}

# Test the method resolution and caching
package main;

# Plan the number of tests
say "1..6";

# Create instances of each class
my $x = X->new();
my $y = Y->new();
my $z = Z->new();

# Call the speak method on each instance to populate the cache
my $output_x = $x->speak();
my $output_y = $y->speak();
my $output_z = $z->speak();

# Verify that the correct method is called for each class
say $output_x eq 'X' ? "ok 1 - X's speak method called" : "not ok 1 - X's speak method not called";
say $output_y eq 'Y' ? "ok 2 - Y's speak method called" : "not ok 2 - Y's speak method not called";
say $output_z eq 'Z' ? "ok 3 - Z's speak method called" : "not ok 3 - Z's speak method not called";

# Call the speak method again to test cache usage
$output_x = $x->speak();
$output_y = $y->speak();
$output_z = $z->speak();

# Verify again to ensure cache keys are correct
say $output_x eq 'X' ? "ok 4 - X's speak method called from cache" : "not ok 4 - X's speak method not called from cache";
say $output_y eq 'Y' ? "ok 5 - Y's speak method called from cache" : "not ok 5 - Y's speak method not called from cache";
say $output_z eq 'Z' ? "ok 6 - Z's speak method called from cache" : "not ok 6 - Z's speak method not called from cache";

