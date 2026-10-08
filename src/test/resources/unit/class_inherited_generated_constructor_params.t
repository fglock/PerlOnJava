use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class InheritedGeneratedConstructorBase {
    field $base :param;
    method base_value { $base }
}

class InheritedGeneratedConstructorChild :isa(InheritedGeneratedConstructorBase) {
    field $data :param;
    method data_value { $data }
}

my $child = InheritedGeneratedConstructorChild->new(base => 10, data => 20);
isa_ok($child, 'InheritedGeneratedConstructorChild',
    'child generated constructor accepts its own parameter through the parent constructor');
is($child->base_value, 10, 'parent generated constructor initializes its parameter');
is($child->data_value, 20, 'child generated constructor initializes its parameter');

my $unknown = eval { InheritedGeneratedConstructorChild->new(base => 10, data => 20, extra => 30); 1 };
ok(!$unknown, 'the child hierarchy still rejects undeclared constructor parameters');
like $@,
    qr/^Unrecogni[sz]ed parameters for "InheritedGeneratedConstructorChild" constructor: extra at /,
    'unknown-parameter diagnostic names the child constructor';

done_testing;
