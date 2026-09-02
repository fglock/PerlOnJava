use strict;
use warnings;
use Test::More tests => 1;

format STDOUT =
.

my $error;
{
    local $@;
    eval { write };
    $error = $@;
}

ok !$error, 'write registers and executes a declared format';
