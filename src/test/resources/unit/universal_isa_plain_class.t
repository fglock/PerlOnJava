use strict;
use warnings;

use Test::More tests => 6;

ok(!UNIVERSAL::isa('ARRAY', 'ARRAY'),
    'an arbitrary string is not its own class');
ok(!UNIVERSAL::isa('Missing::Package', 'Missing::Package'),
    'a missing package name is not a class');
ok(!UNIVERSAL::isa('Runtime::Child', 'Runtime::Parent'),
    'an undeclared runtime class initially misses');

{
    no strict 'refs';
    @{'Runtime::Child::ISA'} = ('Runtime::Parent');
}

ok(UNIVERSAL::isa('Runtime::Child', 'Runtime::Parent'),
    'a runtime ISA assignment overrides an earlier package-cache miss');

{
    package Local::Parent;
    package Local::Child;
    our @ISA = ('Local::Parent');
}

ok(UNIVERSAL::isa('Local::Child', 'Local::Child'),
    'a declared package is its own class');
ok(UNIVERSAL::isa('Local::Child', 'Local::Parent'),
    'a declared package follows inheritance');
