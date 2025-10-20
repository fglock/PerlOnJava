use feature 'say';
use strict;
use Test::More;
use warnings;

# Test classes for overload
{
    package NumberHolder;
    use overload '""' => \&as_string;

    sub new {
        my ($class, $number) = @_;
        return bless { value => $number }, $class;
    }

    sub as_string {
        my $self = shift;
        return "Number: " . $self->{value};
    }
}

{
    package AdvancedNumber;
    use base 'NumberHolder';
    use overload '""' => \&as_string;

    sub as_string {
        my $self = shift;
        return "Advanced " . $self->SUPER::as_string();
    }
}

# Create test objects
my $basic = NumberHolder->new(42);
my $advanced = AdvancedNumber->new(100);

# Test basic string overloading
ok(!("$basic" ne "Number: 42"), 'basic string overload works');

# Test inherited and overridden string overload
ok(!("$advanced" ne "Advanced Number: 100"), 'advanced string overload with SUPER works');

# Test explicit string conversion
ok(!($basic->as_string() ne "Number: 42"), 'explicit string conversion works');

# Test in string concatenation
my $concat = "Value is: " . $basic;
ok(!($concat ne "Value is: Number: 42"), 'string concatenation works');

# Test in interpolation
my $interpolated = "The value: $advanced";
ok(!($interpolated ne "The value: Advanced Number: 100"), 'string interpolation works');

# Test inheritance
ok($advanced->isa('NumberHolder'), 'inheritance verified');

# Test method existence
ok($advanced->can('as_string'), 'string conversion method exists');

# Test overload in eval context
eval {
    my $str = "$basic";
};
ok(!($@), 'overload works in eval context');

# Test stringification in array join
my @objects = ($basic, $advanced);
my $joined = join ", ", @objects;
ok(!($joined ne "Number: 42, Advanced Number: 100"), 'stringification works in join');

done_testing();
