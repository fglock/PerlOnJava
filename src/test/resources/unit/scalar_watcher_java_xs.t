use strict;
use warnings;
use Test::More;

BEGIN {
    if (!eval { require Scalar::Watcher; 1 }) {
        if ($^X =~ /jperl/) {
            require XSLoader;
            XSLoader::load('Scalar::Watcher', '0.002001');
        }
        else {
            plan skip_all => 'Scalar::Watcher required';
        }
    }
}

my $modified;
my $destroyed;
{
    my $value = 1;
    my $cancel = Scalar::Watcher::when_modified($value, sub { $modified = $_[0] });
    Scalar::Watcher::when_destroyed($value, sub { $destroyed = $_[0] });
    $value = 2;
    is $modified, 2, 'modification callback receives assigned value';
    $value++;
    is $modified, 3, 'modification callback receives incremented value';
    undef $cancel;
    $value = 4;
    is $modified, 3, 'destroying canceller removes watcher';
}
is $destroyed, 4, 'destruction callback receives final value';

my $nested_destroyed;
{
    my @values = ([4, 5]);
    Scalar::Watcher::when_destroyed($values[0][1], sub { $nested_destroyed = $_[0] });
    $values[0] = undef;
}
is $nested_destroyed, 5, 'destroying an aggregate notifies watched element slots';

done_testing;
