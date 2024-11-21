use strict;
use feature 'say';
use mro 'c3';

# ASCII Art for Class Hierarchy
# 
#     X     <--root parent class
#    / \
#   Y   Z
#    \ /
#     A
#    / \
#   B   C
#    \ /
#     D     <-- child class

# C3 Method Resolution Order:
#
# C3 linearization ensures that each class appears in the method resolution order only after its parents have been considered. For class D, the C3 linearization would be:
# 
# D: Start with the class itself.
# B: Since D inherits from B and C, and B is listed first, it comes next.
# C: After B, C is considered.
# A: Both B and C inherit from A, so A comes next.
# Y: Since A inherits from Y and Z, and Y is listed first, it comes next.
# Z: After Y, Z is considered.
# X: Finally, X is the root class for both Y and Z.
# 
# Explanation of the Output:
# 
# X's speak method called: X is the root class, and its method is called first.
# Y's speak method called: Y is considered before Z due to the C3 order.
# Z's speak method not called: Z is not called because Y has already satisfied the requirement for A's parent.
# A's speak method called: A is called after Y.
# B's speak method called: B is called as it is the first parent of D.
# C's speak method not called: C is not called because B has already satisfied the requirement for D's parent.
# D's speak method called: Finally, D's method is called.
# 
# Conclusion:
# 
# The C3 method resolution order ensures that each class is only considered once, and it respects the order of inheritance specified in the class definitions. This is why Z and C's methods are not called—they are effectively "skipped" because their functionality is already covered by earlier classes in the resolution order.
#

# Define package X
package X;

sub new {
    return bless {}, shift;
}

sub speak {
    my $self = shift;
    return 'X';
}

# Define package Y, inheriting from X
package Y;

use parent -norequire, 'X';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'Y';
}

# Define package Z, inheriting from X
package Z;

use parent -norequire, 'X';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'Z';
}

# Define package A, inheriting from Y and Z
package A;

use parent -norequire, 'Y', 'Z';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'A';
}

# Define package B, inheriting from A
package B;

use parent -norequire, 'A';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'B';
}

# Define package C, inheriting from A
package C;

use parent -norequire, 'A';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'C';
}

# Define package D, inheriting from B and C
package D;

use parent -norequire, 'B', 'C';

sub speak {
    my $self = shift;
    return $self->SUPER::speak() . 'D';
}

# Test the C3 method resolution and SUPER::method functionality
package main;

# Plan the number of tests
say "1..7";

my $d = D->new();
my $output = $d->speak();

# Check each part of the output

say $output =~ /X/ ? "ok 1 - X's speak method called" : "not ok 1 - X's speak method not called";

say $output =~ /Y/ ? "ok 2 - Y's speak method called" : "not ok 2 - Y's speak method not called";

say $output !~ /Z/ ? "ok 3 - Z's speak method not called" : "not ok 3 - Z's speak method called";

say $output =~ /A/ ? "ok 4 - A's speak method called" : "not ok 4 - A's speak method not called";

say $output =~ /B/ ? "ok 5 - B's speak method called" : "not ok 5 - B's speak method not called";

say $output !~ /C/ ? "ok 6 - C's speak method not called" : "not ok 6 - C's speak method called";

say $output =~ /D/ ? "ok 7 - D's speak method called" : "not ok 7 - D's speak method not called";

