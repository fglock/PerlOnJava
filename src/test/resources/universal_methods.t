use feature 'say';
use strict;
use warnings;

###################
# Perl UNIVERSAL Methods Tests

{
    package MyBase;
    our $VERSION = '1.0';
    sub new { bless {}, shift }
    sub base_method { return "base" }
}

{
    package MyDerived;
    our @ISA = ('MyBase');
    our $VERSION = '1.1';
    sub derived_method { return "derived" }
}

# Create objects
my $base_obj = MyBase->new();
my $derived_obj = MyDerived->new();

# Test can() method
print "not " if !$base_obj->can('base_method');
say "ok # base_obj can base_method";

print "not " if $base_obj->can('derived_method');
say "ok # base_obj cannot can derived_method";

print "not " if !$derived_obj->can('base_method');
say "ok # derived_obj can base_method";

print "not " if !$derived_obj->can('derived_method');
say "ok # derived_obj can derived_method";

# Test isa() method
print "not " if !$base_obj->isa('MyBase');
say "ok # base_obj isa MyBase";

print "not " if $base_obj->isa('MyDerived');
say "ok # base_obj not isa MyDerived";

print "not " if !$derived_obj->isa('MyBase');
say "ok # derived_obj isa MyBase";

print "not " if !$derived_obj->isa('MyDerived');
say "ok # derived_obj isa MyDerived";

# Test DOES() method (same as isa() in this context)
print "not " if !$base_obj->DOES('MyBase');
say "ok # base_obj DOES MyBase";

print "not " if $base_obj->DOES('MyDerived');
say "ok # base_obj not DOES MyDerived";

print "not " if !$derived_obj->DOES('MyBase');
say "ok # derived_obj DOES MyBase";

print "not " if !$derived_obj->DOES('MyDerived');
say "ok # derived_obj DOES MyDerived";

# Test VERSION() method
print "not " if $base_obj->VERSION() ne '1.0';
say "ok # base_obj VERSION is 1.0 <" . $base_obj->VERSION() . ">";

print "not " if $derived_obj->VERSION() ne '1.1';
say "ok # derived_obj VERSION is 1.1";

# Test VERSION() with REQUIRE
eval { $base_obj->VERSION('0.9') };
print "not " if $@;
say "ok # base_obj VERSION >= 0.9";

eval { $base_obj->VERSION('1.1') };
print "not " if !$@;
say "ok # base_obj VERSION < 1.1";
print "not " if $@ !~ /MyBase version 1.1(?:.0)? required/;
say "ok # base_obj VERSION < 1.1 message <" . substr($@, 0, 30) . ">";

$derived_obj->VERSION('1.0');
eval { $derived_obj->VERSION('1.0') };
print "not " if $@;
say "ok # derived_obj VERSION >= 1.0 <$@>";

eval { $derived_obj->VERSION('1.2') };
print "not " if !$@;
say "ok # derived_obj VERSION < 1.2";
print "not " if $@ !~ /MyDerived version 1.2(?:.0)? required/;
say "ok # base_obj VERSION < 1.2 message <" . substr($@, 0, 30) . ">";

