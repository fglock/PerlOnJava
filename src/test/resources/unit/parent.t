use strict;
use warnings;
use Test::More tests => 6;

# Define a base class
{
    package BaseClass;
    sub new {
        my $class = shift;
        return bless {}, $class;
    }
    sub base_method {
        return "Base method called";
    }
}

# Define a derived class using the parent pragma with -norequire
{
    package DerivedClass;
    use parent -norequire, 'BaseClass';
}

# Test inheritance
my $derived = DerivedClass->new();

# Test if the derived class can call the base class method
is($derived->base_method(), "Base method called", 'Derived class can call base class method');

# Test if the derived class is an instance of the base class
isa_ok($derived, 'BaseClass', 'Derived class');

# Test if the derived class is an instance of itself
isa_ok($derived, 'DerivedClass', 'Derived class');

# Test if the derived class cannot call a non-existent method
eval {
    $derived->non_existent_method();
};
ok($@, 'Calling non-existent method throws error');

# Test multiple inheritance
{
    package AnotherBaseClass;
    sub another_method {
        return "Another method called";
    }
}

{
    package MultiDerivedClass;
    use parent -norequire, qw(BaseClass AnotherBaseClass);
}

my $multi_derived = MultiDerivedClass->new();

# Test if the multi-derived class can call methods from both base classes
is($multi_derived->base_method(), "Base method called", 'Multi-derived class can call BaseClass method');
is($multi_derived->another_method(), "Another method called", 'Multi-derived class can call AnotherBaseClass method');
