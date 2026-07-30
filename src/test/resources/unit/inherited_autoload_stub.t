use strict;
use warnings;
use Test::More tests => 1;
use lib 'src/test/resources/unit/lib';
use InheritedAutoLoaderChild;

my $object = bless {}, 'InheritedAutoLoaderChild';
is(
    $object->from_parent,
    'loaded from parent',
    'an inherited AutoSplit stub wins over a child AUTOLOAD',
);
