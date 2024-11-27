use strict;
use warnings;
use feature 'say';

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
print "not " unless $derived->base_method() eq "Base method called"; say "ok # Derived class can call base class method";

# Test if the derived class is an instance of the base class
print "not " unless $derived->isa('BaseClass'); say "ok # Derived class is an instance of BaseClass";

# Test if the derived class is an instance of itself
print "not " unless $derived->isa('DerivedClass'); say "ok # Derived class is an instance of DerivedClass";

# Test if the derived class cannot call a non-existent method
eval {
    $derived->non_existent_method();
};
print "not " unless $@; say "ok # Calling non-existent method throws error";

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
print "not " unless $multi_derived->base_method() eq "Base method called"; say "ok # Multi-derived class can call BaseClass method";
print "not " unless $multi_derived->another_method() eq "Another method called"; say "ok # Multi-derived class can call AnotherBaseClass method";

