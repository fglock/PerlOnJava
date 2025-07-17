use feature 'say';
use strict;
use warnings;

print "1..9\n";

# Test classes for overload
{
    package NumberHolder;
    use overload '0+' => \&as_number, fallback => 1;

    sub new {
        my ($class, $number) = @_;
        return bless { value => $number }, $class;
    }

    sub as_number {
        my $self = shift;
        return $self->{value};
    }
}

{
    package AdvancedNumber;
    use base 'NumberHolder';
    use overload '0+' => \&as_number;

    sub as_number {
        my $self = shift;
        return 2 * $self->SUPER::as_number();
    }
}

# Create test objects
my $basic = NumberHolder->new(42);
my $advanced = AdvancedNumber->new(100);

# Test basic numeric overloading
print "not " if (0 + $basic) != 42;
say "ok # basic numeric overload works";

# Test inherited and overridden numeric overload
print "not " if (0 + $advanced) != 200;
say "ok # advanced numeric overload with SUPER works";

# Test explicit numeric conversion
print "not " if $basic->as_number() != 42;
say "ok # explicit numeric conversion works";

# Test in arithmetic operations
my $sum = 10 + $basic;
print "not " if $sum != 52;
say "ok # numeric addition works";

# Test in multiplication
my $product = 2 * $advanced;
print "not " if $product != 400;
say "ok # numeric multiplication works";

# Test inheritance
print "not " if !$advanced->isa('NumberHolder');
say "ok # inheritance verified";

# Test method existence
print "not " if !$advanced->can('as_number');
say "ok # numeric conversion method exists";

# Test overload in eval context
eval {
    my $num = 0 + $basic;
};
print "not " if $@;
say "ok # overload works in eval context";

# Test numification in array operations
my @objects = ($basic, $advanced);
my $sum_all = $objects[0] + $objects[1];
print "not " if $sum_all != 242;
say "ok # numification works in array operations";
