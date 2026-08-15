use strict;
use warnings;
use Test::More tests => 4;
use Object::Pad 0.66;

class ObjectPadNativeBase {
    field $value :param;
    method value { $value }
}

class ObjectPadNativeChild :isa(ObjectPadNativeBase) {
    field $extra :param = 2;
    method total ($base = $extra) { $self->value + $base }
}

my $object = ObjectPadNativeChild->new(value => 40);
isa_ok($object, 'ObjectPadNativeChild');
is($object->value, 40, 'Object::Pad syntax exposes inherited field method');
is($object->total, 42, 'Object::Pad syntax supports parameters and inheritance');
is($object->total(3), 43, 'method signature default can read a class field');
