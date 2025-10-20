use feature 'say';
use strict;
use Test::More;
use warnings;

{
    package NotHolder;
    use overload
        'bool' => \&as_bool,
        '!' => \&negate,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_bool {
        my $self = shift;
        return $self->{value};
    }

    sub negate {
        my $self = shift;
        return NotHolder->new(!$self->{value});
    }
}

# Create test objects
my $true_obj = NotHolder->new(1);
my $false_obj = NotHolder->new(0);

# Test direct negation
ok(!$true_obj->as_bool() == 0, 'direct negation of true value works');

ok(!$false_obj->as_bool() == 1, 'direct negation of false value works');

# Test overloaded negation operator
my $negated_true = !$true_obj;
ok(!($negated_true->as_bool()), 'negation operator works on true object');

my $negated_false = !$false_obj;
ok($negated_false->as_bool(), 'negation operator works on false object');

# Test double negation
my $double_neg = !!$true_obj;
ok($double_neg->as_bool(), 'double negation returns to original value');

# Test in conditional contexts
if (!$true_obj) {
    print "not ";
}
say "ok # negation works in if condition";

# Test chained negation
my $triple_neg = !!!$true_obj;
ok(!($triple_neg->as_bool()), 'triple negation works');

# Test negation in boolean expressions
ok((!$true_obj || $true_obj), 'negation works in OR expression');

ok(!((!$true_obj && $true_obj)), 'negation works in AND expression');

# Test negation with comparison operators
ok(!$false_obj == !$false_obj, 'negated values compare correctly');

# Test in array context
my @objects = (!$true_obj, !$false_obj);
print "not " if $objects[0]->as_bool();
ok($objects[1]->as_bool(), 'negation works in array context');

done_testing();
