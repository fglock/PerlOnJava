use strict;
use warnings;
use Test::More;

BEGIN {
    package ImportedLengthFunction;

    sub length {
        return join ':', @_;
    }

    sub import {
        my ($class, $caller) = @_;
        no strict 'refs';
        *{"${caller}::length"} = \&length;
    }

    ImportedLengthFunction->import('main');
}

is(length(1, 'km'), '1:km',
    'an imported two-argument length sub overrides the CORE builtin');
is(CORE::length('abc'), 3,
    'explicit CORE::length still selects the builtin');

done_testing;
