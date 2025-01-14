use feature 'say';
use strict;
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
print "not " if "$basic" ne "Number: 42";
say "ok # basic string overload works";

# Test inherited and overridden string overload
print "not " if "$advanced" ne "Advanced Number: 100";
say "ok # advanced string overload with SUPER works";

# Test explicit string conversion
print "not " if $basic->as_string() ne "Number: 42";
say "ok # explicit string conversion works";

# Test in string concatenation
my $concat = "Value is: " . $basic;
print "not " if $concat ne "Value is: Number: 42";
say "ok # string concatenation works";

# Test in interpolation
my $interpolated = "The value: $advanced";
print "not " if $interpolated ne "The value: Advanced Number: 100";
say "ok # string interpolation works";

# Test inheritance
print "not " if !$advanced->isa('NumberHolder');
say "ok # inheritance verified";

# Test method existence
print "not " if !$advanced->can('as_string');
say "ok # string conversion method exists";

# Test overload in eval context
eval {
    my $str = "$basic";
};
print "not " if $@;
say "ok # overload works in eval context";

# Test stringification in array join
my @objects = ($basic, $advanced);
my $joined = join ", ", @objects;
print "not " if $joined ne "Number: 42, Advanced Number: 100";
say "ok # stringification works in join";
