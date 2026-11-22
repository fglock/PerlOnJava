use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    use Object::Pad 0.800;

    class ObjectPad0800FieldInitializer {
        field $base :param;
        field $doubled = $base * 2;
        field $label :param = 'item';

        method describe { "$label:$base:$doubled" }
    }
    1;
};
if (!$loaded && $@ =~ /Can't locate Object\/Pad\.pm/) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}
die $@ unless $loaded;

my $object = ObjectPad0800FieldInitializer->new(base => 21);
is($object->describe, 'item:21:42',
    '0.800 field initializers evaluate expressions using constructor fields');
my $custom = ObjectPad0800FieldInitializer->new(base => 4, label => 'sample');
is($custom->describe, 'sample:4:8',
    '0.800 field initializers preserve supplied :param values');

done_testing();
