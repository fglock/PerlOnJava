use strict;
use warnings;
use Test::More;

use feature 'class';
no warnings 'experimental::class';

class DefaultAccessorNames {
    field $_alpha :reader :writer = 'A';
    field $__beta :reader :writer = 'B';
    field $_42 :reader(get_42) :writer = 'forty-two';
}

my $object = DefaultAccessorNames->new;
is($object->alpha, 'A', 'reader strips one leading underscore');
is($object->_beta, 'B', 'reader preserves the second leading underscore');

is($object->set_alpha('AA'), $object, 'writer returns the object');
is($object->alpha, 'AA', 'writer strips one leading underscore');
is($object->set__beta('BB'), $object,
    'writer preserves the second leading underscore');
is($object->_beta, 'BB', 'double-underscore field round trips');

is($object->set_42('answer'), $object,
    'default writer accepts a numeric name after the underscore');
is($object->get_42, 'answer', 'explicit reader name remains exact');

done_testing;
