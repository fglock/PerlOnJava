use strict;
use warnings;

use Test::More tests => 4;

package Fixture::CompileTimeOur;

our $VERSION = 'runtime value';
our (@ISA, %REGISTRY);

BEGIN {
    Test::More::ok(
        exists $Fixture::CompileTimeOur::{VERSION},
        'scalar our declaration creates its glob during compilation',
    );
    Test::More::ok(
        exists $Fixture::CompileTimeOur::{ISA},
        'array our declaration creates its glob during compilation',
    );
    Test::More::ok(
        exists $Fixture::CompileTimeOur::{REGISTRY},
        'hash our declaration creates its glob during compilation',
    );
    Test::More::ok(
        !defined $Fixture::CompileTimeOur::VERSION,
        'runtime initializer has not executed in the following BEGIN block',
    );
}
