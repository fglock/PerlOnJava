use strict;
use warnings;
use Test::More;

{
    package FunctionParametersTypedInvocantType;
    sub check { defined $_[1] }
    sub get_message { 'UNDEFINED' }
}

use constant Defined => bless {}, 'FunctionParametersTypedInvocantType';
use Function::Parameters qw(:std :modifiers);

method typed_method(Defined $this: Defined $value) { $value }

my $expected_call_line = __LINE__ + 1;
my $error = eval { typed_method(undef, undef); 1 };
ok(!$error, 'typed explicit method invocants and parameters are checked');
like($@, qr/In method typed_method: invocant \(\$this\): UNDEFINED at .* line $expected_call_line\./,
    'type failures identify the invocant and report the call site');

done_testing;
