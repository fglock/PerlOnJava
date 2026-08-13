use strict;
use warnings;

use B ();
use Class::XSAccessor accessors => { value => 'value' };

print "1..2\n";

my $object = bless { value => 41 }, __PACKAGE__;
print $object->value == 41
    ? "ok 1 - generated accessor remains callable\n"
    : "not ok 1 - generated accessor remains callable\n";

my $name = B::svref_2object(\&value)->GV->NAME;
print $name eq 'value'
    ? "ok 2 - generated accessor has its installed CV name\n"
    : "not ok 2 - generated accessor has its installed CV name\n";
