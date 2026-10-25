use strict;
use warnings;
use Test::More;
use Function::Parameters;

{
    package FunctionParametersCoercingType;

    sub check { $_[1] eq 'Type_C:good' }
    sub get_message { 'value must be Type_C:good' }
    sub has_coercion { 1 }
    sub coerce {
        my $value = $_[1];
        $value =~ s/\?+\z//;
        return $value;
    }
}

use constant FunctionParametersCoercingTypeConstraint =>
    bless {}, 'FunctionParametersCoercingType';

fun constrained_value(FunctionParametersCoercingTypeConstraint $value) { $value }

is(constrained_value('Type_C:good???'), 'Type_C:good',
    'coerce a value before validating the parameter');

my $valid = eval { constrained_value('Type_C:bad'); 1 };
ok(!$valid, 'reject a value after its coercion fails the type check');
like($@, qr/value must be Type_C:good/,
    'report the constraint message for a failed coerced value');

done_testing;
