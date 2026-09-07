use strict;
use warnings;
use Test::More;

use Params::Validate qw(validate SCALAR);

sub validate_named_arguments {
    my %arguments = validate(
        @_, {
            required => { type => SCALAR },
            optional => { type => SCALAR, optional => 1 },
        },
    );

    return exists $arguments{optional} ? 'explicit-undef' : 'omitted';
}

sub false_if_result {
    if ($_[0]) { 'taken' }
}

is(
    false_if_result(0),
    0,
    'an untaken if without else returns its false condition value',
);

is(
    false_if_result(1),
    'taken',
    'a taken if without else returns its branch value',
);

is(
    validate_named_arguments(required => 'value'),
    'omitted',
    'omitted optional named arguments are not materialized as undef',
);

eval { validate_named_arguments(required => 'value', optional => undef) };
like(
    $@,
    qr/The 'optional' parameter \(undef\).*not one of the allowed types: scalar/,
    'explicit undef remains distinguishable from an omitted argument',
);

done_testing;
