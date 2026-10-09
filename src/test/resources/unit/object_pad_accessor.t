use strict;
use warnings;
use Test::More;
use Object::Pad 0.66;

class ObjectPadAccessorFields {
    has $value :accessor = 'initial';
    has $_label :accessor;
    has $custom :accessor(custom_value) = 7;
}

my $object = ObjectPadAccessorFields->new;
is($object->value, 'initial', ':accessor reads its initialized field');
is($object->value('updated'), 'updated', ':accessor setter returns the assigned value');
is($object->value, 'updated', ':accessor reads the updated value');
is($object->value(undef), undef, ':accessor can explicitly assign undef');
ok(!defined $object->value, ':accessor distinguishes an undef setter from a getter');
is($object->label('named'), 'named', 'default accessor name drops one leading underscore');
is($object->label, 'named', 'default accessor name reads after setting');
is($object->custom_value, 7, ':accessor(NAME) uses its explicit name');
is($object->custom_value(8), 8, ':accessor(NAME) also writes');

my $too_many_ok = eval { $object->value(1, 2); 1 };
ok(!$too_many_ok, ':accessor rejects more than one value argument');

my $aggregate_ok = eval q{
    class ObjectPadArrayAccessor { has @items :accessor }
    1;
};
ok(!$aggregate_ok, ':accessor is limited to scalar fields');
like($@, qr/Cannot apply a :accessor attribute to a non-scalar field/,
    'aggregate accessor rejection has a useful diagnostic');

done_testing;
