use feature 'say';
use strict;
use warnings;

print "1..11\n";

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
print "not " unless !$true_obj->as_bool() == 0;
say "ok # direct negation of true value works";

print "not " unless !$false_obj->as_bool() == 1;
say "ok # direct negation of false value works";

# Test overloaded negation operator
my $negated_true = !$true_obj;
print "not " if $negated_true->as_bool();
say "ok # negation operator works on true object";

my $negated_false = !$false_obj;
print "not " unless $negated_false->as_bool();
say "ok # negation operator works on false object";

# Test double negation
my $double_neg = !!$true_obj;
print "not " unless $double_neg->as_bool();
say "ok # double negation returns to original value";

# Test in conditional contexts
if (!$true_obj) {
    print "not ";
}
say "ok # negation works in if condition";

# Test chained negation
my $triple_neg = !!!$true_obj;
print "not " if $triple_neg->as_bool();
say "ok # triple negation works";

# Test negation in boolean expressions
print "not " unless (!$true_obj || $true_obj);
say "ok # negation works in OR expression";

print "not " if (!$true_obj && $true_obj);
say "ok # negation works in AND expression";

# Test negation with comparison operators
print "not " unless !$false_obj == !$false_obj;
say "ok # negated values compare correctly";

# Test in array context
my @objects = (!$true_obj, !$false_obj);
print "not " if $objects[0]->as_bool();
print "not " unless $objects[1]->as_bool();
say "ok # negation works in array context";