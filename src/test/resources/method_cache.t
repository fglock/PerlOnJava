use strict;
use warnings;
use Test::More tests => 6;

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

# Create instances of each class
my $x = X->new();
my $y = Y->new();
my $z = Z->new();

# Call the speak method on each instance to populate the cache
my $output_x = $x->speak();
my $output_y = $y->speak();
my $output_z = $z->speak();

# Verify that the correct method is called for each class
is($output_x, 'X', "X's speak method called");
is($output_y, 'Y', "Y's speak method called");
is($output_z, 'Z', "Z's speak method called");

# Call the speak method again to test cache usage
$output_x = $x->speak();
$output_y = $y->speak();
$output_z = $z->speak();

# Verify again to ensure cache keys are correct
is($output_x, 'X', "X's speak method called from cache");
is($output_y, 'Y', "Y's speak method called from cache");
is($output_z, 'Z', "Z's speak method called from cache");
