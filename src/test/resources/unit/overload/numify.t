use feature 'say';
use strict;
use Test::More;
use warnings;

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
ok(!((0 + $basic) != 42), 'basic numeric overload works');

# Test inherited and overridden numeric overload
ok(!((0 + $advanced) != 200), 'advanced numeric overload with SUPER works');

# Test explicit numeric conversion
ok(!($basic->as_number() != 42), 'explicit numeric conversion works');

# Test in arithmetic operations
my $sum = 10 + $basic;
ok(!($sum != 52), 'numeric addition works');

# Test in multiplication
my $product = 2 * $advanced;
ok(!($product != 400), 'numeric multiplication works');

# Test inheritance
ok($advanced->isa('NumberHolder'), 'inheritance verified');

# Test method existence
ok($advanced->can('as_number'), 'numeric conversion method exists');

# Test overload in eval context
eval {
    my $num = 0 + $basic;
};
ok(!($@), 'overload works in eval context');

# Test numification in array operations
my @objects = ($basic, $advanced);
my $sum_all = $objects[0] + $objects[1];
ok(!($sum_all != 242), 'numification works in array operations');

done_testing();
