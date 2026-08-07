use strict;
use warnings;

use Test::More tests => 2;

{
    package Local::Child;
}

ok(isa Local::Child('Local::Child'), 'feature-disabled isa supports indirect-object syntax');
ok(isa Local::Child('UNIVERSAL'), 'indirect isa reaches UNIVERSAL');
