use strict;
use warnings;
use Test::More;

use B;
use B::Flags;

like(B::main_root->flagspv, qr/WANT_VOID/, 'OP flags expose context');
like(B::main_root->privatepv, qr/REFCOUNTED/, 'OP private flags are available');

my $number = 42;
my $sv = B::svref_2object(\$number);
like($sv->flagspv, qr/IOK/, 'SV numeric flags are named');

my @values = (1, 2);
my $av = B::svref_2object(\@values);
like($av->flagspv, qr/REAL/, 'portable array structural flag is reported');

done_testing;
