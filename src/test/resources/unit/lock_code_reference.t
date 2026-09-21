use strict;
use warnings;
use Test::More tests => 2;

my $code = lock &missing_subroutine;
ok(ref($code), 'lock accepts an undeclared named code reference');
is(ref($code), 'CODE', 'lock returns the code reference');
