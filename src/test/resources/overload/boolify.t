use feature 'say';
use strict;
use warnings;

# Test classes for overload
{
    package BoolHolder;
    use overload 'bool' => \&as_bool, fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_bool {
        my $self = shift;
        return $self->{value};
    }
}

{
    package AdvancedBool;
    use base 'BoolHolder';
    use overload 'bool' => \&as_bool;

    sub as_bool {
        my $self = shift;
        return !$self->SUPER::as_bool();
    }
}

# Create test objects
my $basic = BoolHolder->new(1);
my $false_basic = BoolHolder->new(0);
my $advanced = AdvancedBool->new(1);

# Test basic boolean overloading
print "not " unless $basic;
say "ok # basic boolean overload works (true)";

print "not " if $false_basic;
say "ok # basic boolean overload works (false)";

# Test inherited and overridden boolean overload
print "not " if $advanced;
say "ok # advanced boolean overload with SUPER works";

# Test explicit boolean conversion
print "not " unless $basic->as_bool();
say "ok # explicit boolean conversion works";

# Test in if conditions
if ($basic) {
    print "not " unless $basic;
    say "ok # boolean in if condition works";
}

# Test in logical operations
my $and_result = $basic && $false_basic;
print "not " if $and_result;
say "ok # logical AND works";

my $or_result = $basic || $false_basic;
print "not " unless $or_result;
say "ok # logical OR works";

# Test logical NOT operator
print "not " if !$basic;
say "ok # logical NOT works with true value";

print "not " unless !$false_basic;
say "ok # logical NOT works with false value";

# Test double negation
print "not " unless !!$basic;
say "ok # double negation works";

# Test inheritance
print "not " if !$advanced->isa('BoolHolder');
say "ok # inheritance verified";

# Test method existence
print "not " if !$advanced->can('as_bool');
say "ok # boolean conversion method exists";

# Test overload in eval context
eval {
    my $bool = $basic ? 1 : 0;
};
print "not " if $@;
say "ok # overload works in eval context";

# Test boolification in array operations
my @objects = ($basic, $advanced);
print "not " unless $objects[0];
print "not " if $objects[1];
say "ok # boolification works in array operations";
