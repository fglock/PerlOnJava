use strict;
use warnings;
use Test::More;
use Scalar::Util ();

BEGIN {
    eval { require Module::Generic; 1 }
        or plan skip_all => 'Module::Generic required';
}

my $object = bless {}, 'Module::Generic';
my $array = [];
my $hash = {};
my $code = sub { 1 };
my $scalar = 1;

is_deeply($object->_get_args_as_array(1, 2), [1, 2], 'wraps arguments in an array');
ok($object->_is_array($array), 'recognizes array references');
ok($object->_is_hash($hash), 'recognizes hash references');
ok($object->_is_code($code), 'recognizes code references');
ok($object->_is_scalar(\$scalar), 'recognizes scalar references');
ok($object->_is_integer('-42'), 'recognizes integer strings');
ok(!$object->_is_integer('4.2'), 'rejects non-integer strings');
ok($object->_is_number(42), 'recognizes numeric scalars');
ok(!$object->_is_number('42'), 'does not treat numeric strings as numeric scalars');
ok($object->_is_object($object), 'recognizes blessed references');
ok(!$object->_is_class_loaded_xs('No::Such::Class'), 'rejects an unloaded class');
ok($object->_is_class_loaded_xs('Module::Generic'), 'recognizes a loaded class');
is($object->_refaddr($array), Scalar::Util::refaddr($array), 'returns the referent address');

done_testing;
