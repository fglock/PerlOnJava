use strict;
use warnings;
use Test::More tests => 2;

eval { UNIVERSAL::DOES([], 'RoleName') };
like(
    $@,
    qr/Can't call method "DOES" on unblessed reference/,
    'direct UNIVERSAL::DOES names DOES in an unblessed-reference error',
);

@UNIVERSAL::ISA = ('UniversalParent');
ok(
    UndeclaredUniversalChild->isa('UniversalParent'),
    'undeclared classes inherit explicit UNIVERSAL parents',
);
