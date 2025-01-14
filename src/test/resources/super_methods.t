use feature 'say';
use strict;
use warnings;

# Test classes for SUPER:: calls
{
    package Parent;
    sub new { bless {}, shift }
    sub greet { return "Parent says hello" }
    sub identify { return "I am Parent" }
}

{
    package Child;
    our @ISA = ('Parent');

    sub greet {
        my $self = shift;
        return "Child says: " . $self->SUPER::greet();
    }

    sub identify {
        my $self = shift;
        return "Child and " . $self->SUPER::identify();
    }
}

# Create objects
my $parent = Parent->new();
my $child = Child->new();

# Test regular parent method call
print "not " if $parent->greet() ne "Parent says hello";
say "ok # parent greet works correctly";

# Test parent identify
print "not " if $parent->identify() ne "I am Parent";
say "ok # parent identify works correctly";

# Test child method with SUPER:: call
print "not " if $child->greet() ne "Child says: Parent says hello";
say "ok # child greet with SUPER works correctly";

# Test child identify with SUPER:: call
print "not " if $child->identify() ne "Child and I am Parent";
say "ok # child identify with SUPER works correctly";

# Test method existence
print "not " if !$child->can('greet');
say "ok # child can greet";

print "not " if !$child->can('identify');
say "ok # child can identify";

# Test inheritance chain
print "not " if !$child->isa('Parent');
say "ok # child isa Parent verified";

# Test SUPER:: in eval context
eval {
    $child->greet();
};
print "not " if $@;
say "ok # SUPER:: call works in eval context";

# Test method override without SUPER::
{
    package Child;
    sub new_method { return "Child only" }
}

print "not " if $child->new_method() ne "Child only";
say "ok # child-only method works";

print "not " if defined eval { $child->SUPER::new_method() };
say "ok # SUPER:: to non-existent parent method fails correctly";
