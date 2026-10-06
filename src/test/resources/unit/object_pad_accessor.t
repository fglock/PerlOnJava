use strict;
use warnings;
use Test::More;

BEGIN {
    if (!eval { require Object::Pad; 1 }) {
        my $error = $@;
        if ($error =~ /\ACan't locate Object\/Pad\.pm in \@INC/) {
            plan skip_all => 'Object::Pad is not installed in the host Perl';
        }
        die $error;
    }
    Object::Pad->import;
}

class ObjectPadAccessorExample {
    field $value :accessor = 'initial';
    field $_private :accessor = 'private';
    field $renamed :accessor(read_renamed) = 'renamed';
}

class ObjectPadAccessorChild :isa(ObjectPadAccessorExample) {
    field $child_value :accessor = 'child';
}

my $object = ObjectPadAccessorExample->new;
my $other = ObjectPadAccessorExample->new;
is($object->value, 'initial', ':accessor reads its initialized field');
is($object->value('updated'), 'updated', ':accessor writes and returns the field');
is($object->value, 'updated', ':accessor exposes the new field value');
is($other->value, 'initial', ':accessor values belong to each instance');
is($object->value(0), 0, ':accessor stores a false zero value');
is($object->value(''), '', ':accessor stores a false empty string');
is($object->value(undef), undef, ':accessor stores undef');
is($object->value, undef, ':accessor reads back undef');
is($object->private, 'private', ':accessor drops a leading field underscore');
is($object->read_renamed, 'renamed', ':accessor accepts an explicit method name');
is($object->read_renamed('renamed again'), 'renamed again',
    ':accessor writes through an explicitly named method');
my $child = ObjectPadAccessorChild->new;
is($child->value, 'initial', ':accessor is inherited by a child class');
is($child->child_value, 'child', ':accessor reads a child field');

my $error = eval { $object->value('one', 'two'); 1 } ? '' : $@;
like($error, qr/Too many arguments/, ':accessor rejects more than one value');

done_testing();
