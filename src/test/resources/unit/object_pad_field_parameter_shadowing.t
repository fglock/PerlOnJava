use strict;
use warnings;
use Test::More;

if (!eval { require Object::Pad; 1 }) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}

my $source = <<'PERL';
use Object::Pad 0.800;

class Test::ObjectPad::FieldParameterShadowing {
    field $value = 'field default';

    method echo ($value = $value) { $value }
}

my $object = Test::ObjectPad::FieldParameterShadowing->new;
our $provided = $object->echo('parameter value');
our $defaulted = $object->echo;
PERL

our $provided;
our $defaulted;
my $compiled = eval $source;
ok($compiled, 'method signature and field compile') or diag $@;
if ($compiled) {
    is($provided, 'parameter value', 'method parameter shadows same-named field');
    is($defaulted, 'field default', 'parameter default can read the shadowed field');
}

done_testing();
