use strict;
use warnings;
use Test::More;

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

# Test regular parent method calls
is($parent->greet(), "Parent says hello", 'parent greet works correctly');
is($parent->identify(), "I am Parent", 'parent identify works correctly');

# Test child methods with SUPER:: calls
is($child->greet(), "Child says: Parent says hello", 'child greet with SUPER works correctly');
is($child->identify(), "Child and I am Parent", 'child identify with SUPER works correctly');

# Test method existence
can_ok($child, 'greet');
can_ok($child, 'identify');

# Test inheritance chain
isa_ok($child, 'Parent', 'child');

# Test SUPER:: in eval context
eval {
    $child->greet();
};
is($@, '', 'SUPER:: call works in eval context');

# Test method override without SUPER::
{
    package Child;
    sub new_method { return "Child only" }
}

is($child->new_method(), "Child only", 'child-only method works');

eval { $child->SUPER::new_method() };
ok(defined($@), 'SUPER:: to non-existent parent method fails correctly');

done_testing();
