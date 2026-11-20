use strict;
use warnings;
use Test::More;

{
    package FunctionParametersTypedSlurpyType;
    sub check { defined $_[1] }
    sub get_message { 'UNDEFINED' }
}

use constant Defined => bless {}, 'FunctionParametersTypedSlurpyType';
use Function::Parameters;

fun typed_tail(Defined $first, $middle, Defined @tail) { }

my $ok = eval { typed_tail('defined', undef, 'defined', undef); 1 };
ok(!$ok, 'a typed slurpy parameter checks its supplied elements');
like($@, qr/\AIn fun typed_tail: parameter 3 \(\@tail\): UNDEFINED/,
    'typed slurpy diagnostics use the parameter ordinal');

done_testing;
