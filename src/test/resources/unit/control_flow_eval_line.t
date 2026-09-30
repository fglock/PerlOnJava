use strict;
use Test::More;

our @a;
my $error;
#line 7 "virtual.pl"
eval { @a = (1, 2, 3); { @a = sort { last; } @a; } };
$error = $@;
#line 1 "control_flow_eval_line.t"
like $error, qr/Can't "last" outside a loop block at virtual\.pl line 7\./,
    'non-local sort control-flow errors use the source directive location';

done_testing();
