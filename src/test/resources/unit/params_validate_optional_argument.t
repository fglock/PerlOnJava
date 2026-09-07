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
