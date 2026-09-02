use strict;
use warnings;

use Test2::V0;
use Test2::Tools::Refcount qw(is_oneref);
use Scalar::Util qw(weaken);

our %ROOT;

my $object = bless {}, 'CapturedGlobalOwnerRelease';
my $weak_object = $object;
weaken($weak_object);

$ROOT{object} = $object;

my $callback = do {
    my $captured = $object;
    sub { $captured };
};

undef $callback;
delete $ROOT{object};

is_oneref($object,
    'released callback capture does not outlive a deleted global owner');

done_testing;
