use feature 'say';
use strict;
use Test::More;
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
ok($basic, 'basic boolean overload works (true)');

ok(!($false_basic), 'basic boolean overload works (false)');

# Test inherited and overridden boolean overload
ok(!($advanced), 'advanced boolean overload with SUPER works');

# Test explicit boolean conversion
ok($basic->as_bool(), 'explicit boolean conversion works');

# Test in if conditions
if ($basic) {
    ok($basic, 'boolean in if condition works');
}

# Test in logical operations
my $and_result = $basic && $false_basic;
ok(!($and_result), 'logical AND works');

my $or_result = $basic || $false_basic;
ok($or_result, 'logical OR works');

# Test logical NOT operator
ok($basic, 'logical NOT works with true value');

ok(!$false_basic, 'logical NOT works with false value');

# Test double negation
ok(!!$basic, 'double negation works');

# Test inheritance
ok($advanced->isa('BoolHolder'), 'inheritance verified');

# Test method existence
ok($advanced->can('as_bool'), 'boolean conversion method exists');

# Test overload in eval context
eval {
    my $bool = $basic ? 1 : 0;
};
ok(!($@), 'overload works in eval context');

# Test boolification in array operations
my @objects = ($basic, $advanced);
print "not " unless $objects[0];
ok(!($objects[1]), 'boolification works in array operations');

done_testing();
