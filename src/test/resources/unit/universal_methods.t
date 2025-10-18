use strict;
use warnings;
use Test::More;

# Package definitions
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

subtest 'can() method tests' => sub {
    ok($base_obj->can('base_method'), 'base_obj can base_method');
    ok(!$base_obj->can('derived_method'), 'base_obj cannot derived_method');
    ok($derived_obj->can('base_method'), 'derived_obj can base_method');
    ok($derived_obj->can('derived_method'), 'derived_obj can derived_method');
};

subtest 'isa() method tests' => sub {
    ok($base_obj->isa('MyBase'), 'base_obj isa MyBase');
    ok(!$base_obj->isa('MyDerived'), 'base_obj not isa MyDerived');
    ok($derived_obj->isa('MyBase'), 'derived_obj isa MyBase');
    ok($derived_obj->isa('MyDerived'), 'derived_obj isa MyDerived');
};

subtest 'DOES() method tests' => sub {
    ok($base_obj->DOES('MyBase'), 'base_obj DOES MyBase');
    ok(!$base_obj->DOES('MyDerived'), 'base_obj not DOES MyDerived');
    ok($derived_obj->DOES('MyBase'), 'derived_obj DOES MyBase');
    ok($derived_obj->DOES('MyDerived'), 'derived_obj DOES MyDerived');
};

subtest 'VERSION() method tests' => sub {
    is($base_obj->VERSION(), '1.0', 'base_obj VERSION is 1.0');
    is($derived_obj->VERSION(), '1.1', 'derived_obj VERSION is 1.1');
};

subtest 'VERSION() with requirements' => sub {
    # Test VERSION() with version that should pass
    eval { $base_obj->VERSION('0.9') };
    ok(!$@, 'base_obj VERSION >= 0.9');

    # Test VERSION() with version that should fail
    eval { $base_obj->VERSION('1.1') };
    ok($@, 'base_obj VERSION < 1.1 throws exception');
    like($@, qr/MyBase version 1.1(?:.0)? required/, 'base_obj VERSION error message correct');

    # Test derived object with passing version
    eval { $derived_obj->VERSION('1.0') };
    ok(!$@, 'derived_obj VERSION >= 1.0');

    # Test derived object with failing version
    eval { $derived_obj->VERSION('1.2') };
    ok($@, 'derived_obj VERSION < 1.2 throws exception');
    like($@, qr/MyDerived version 1.2(?:.0)? required/, 'derived_obj VERSION error message correct');
};

done_testing();
