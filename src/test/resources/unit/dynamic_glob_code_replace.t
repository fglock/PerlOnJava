use strict;
use warnings;

use Test::More tests => 3;

{
    package Local::HookTarget;
    sub method { 'original' }
}

my $object = bless {}, 'Local::HookTarget';
my $glob = \*Local::HookTarget::method;
my $original = *$glob{CODE};

is($object->method, 'original', 'original method is callable');

{
    no warnings 'redefine';
    *$glob = sub { 'replacement' };
}
is($object->method, 'replacement', 'method lookup observes replacement through glob ref');

{
    no warnings 'redefine';
    *$glob = $original;
}
is($object->method, 'original', 'method lookup observes restore through glob ref');
