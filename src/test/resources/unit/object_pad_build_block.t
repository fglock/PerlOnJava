use strict;
use warnings;
use Test::More;

# Keep the Object::Pad syntax inside eval so system Perl can skip this focused
# extension test when the optional upstream distribution is not installed.
my $loaded = eval q{
    use Object::Pad 0.66;

    class ObjectPadBuildBlock {
        field $value :param;

        BUILD {
            $value += 2;
        }

        method first_argument { shift }
        method value { $value }
    }
};
if (!$loaded && $@ =~ /Can't locate Object\/Pad\.pm/) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}
die $@ unless $loaded;
ok($loaded, 'class declaration at the end of eval returns true');

my $object = ObjectPadBuildBlock->new(value => 40);
is($object->value, 42,
    'BUILD runs after :param field initialization and can update the field');
is($object->first_argument('passed'), 'passed',
    'bare shift in an Object::Pad method shifts its argument list');

done_testing;
