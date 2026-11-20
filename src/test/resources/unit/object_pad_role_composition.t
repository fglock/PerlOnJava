use strict;
use warnings;
use Test::More;

if (!eval { require Object::Pad; 1 }) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}

my $source = <<'PERL';
use Object::Pad 0.800;

role Test::ObjectPad::Role :repr(HASH) :compat(invokable) {
    method role_value { 'from role' }
}

class Test::ObjectPad::Class :repr(HASH) :does(Test::ObjectPad::Role) {
    field $value :param = 'from class';
    method value { $value }
    method accepts_named_pairs (%) { 'accepted' }
}
{
    package Test::ObjectPad::Class;
    use Object::Pad ':experimental(mop)';
    Object::Pad::MOP::Class->for_caller->add_method(
        mop_value => method { 'from MOP' });
}

use Object::Pad::ClassAttr::Struct;
class Test::ObjectPad::Struct :Struct(readonly) {
    field $name;
    field $direction;
}

BEGIN {
    package Future;
    our $VERSION = '0.52';
    $INC{'Future.pm'} = __FILE__;

    sub AWAIT_NEW_DONE {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }
    sub get { return $_[0]{value} }
}

use Future::AsyncAwait;
no strict 'vars';
class Test::ObjectPad::UnitClass :repr(HASH);
field $_value;
ADJUST { $_value = 'from ADJUST'; }
field $_declared_after_adjust;
async method set_value ($value) {
    $_value = $value;
    return $_value;
}
package main;

our $object = Test::ObjectPad::Class->new;
our $struct = Test::ObjectPad::Struct->new_values('GPIO', 'input');
our $unit_class = Test::ObjectPad::UnitClass->new;
our $unit_initial_value = $unit_class->{_value};
our $unit_result = $unit_class->set_value('unit method signature')->get;
sub ignored_first_parameter ( $, $value ) { $value }
our $signature_result = ignored_first_parameter(1, 'signature');
PERL

our $object;
our $struct;
our $signature_result;
our $unit_result;
our $unit_initial_value;
my $compiled = eval $source;
ok($compiled, 'Object::Pad repr and role attributes compile') or diag $@;
if ($compiled) {
    is($object->value, 'from class',
        'class fields keep their hash representation');
    is($object->role_value, 'from role',
        'the class dispatches methods composed from its role');
    is($object->mop_value, 'from MOP',
        'the supported Object::Pad MOP can add a method to a class');
    is($object->accepts_named_pairs(one => 1, two => 2), 'accepted',
        'method slurpy-hash validation ignores the already shifted receiver');
    is($signature_result, 'signature',
        'Object::Pad enables Perl signatures including an ignored positional parameter');
    is($struct->name, 'GPIO', ':Struct(readonly) creates a positional value constructor');
    is($struct->direction, 'input', ':Struct(readonly) initializes each field');
    is($unit_result, 'unit method signature',
        'unit-class async method signatures preserve positional arguments');
    is($unit_initial_value, 'from ADJUST',
        'later unit-class fields preserve earlier ADJUST initialization');
}

done_testing();
