use strict;
use warnings;
use Test::More;

plan tests => 11;

# Basic ok tests
ok(1, 'True value passes');
ok(42, 'Number passes');
ok('foo', 'String passes');

# Testing equality
is('apple', 'apple', 'String equality works');
isnt('apple', 'orange', 'String inequality works');

# Regular expression matching
like('hello world', qr/world/, 'Pattern matching works');
unlike('hello world', qr/banana/, 'Negative pattern matching works');

# Numeric comparisons
cmp_ok(42, '>', 10, 'Numeric comparison works');

# Object testing
{
    package DummyClass;
    sub new { bless {}, shift }
    sub test_method { 1 }
}

my $obj = DummyClass->new();

# Object tests
isa_ok($obj, 'DummyClass', 'Object type check');
can_ok('DummyClass', 'test_method');

# Subtests
subtest 'Grouped tests' => sub {
    ok(1, 'First grouped test');
    ok(1, 'Second grouped test');
    is('test', 'test', 'Third grouped test');
};

done_testing();

