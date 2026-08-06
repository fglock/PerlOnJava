#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 1;

my $error = do {
    local $@;
    eval q{
        use strict;
        splice @$strict_deref_array, 3 1;
        my $strict_deref_array = [qw/a b c/];
    };
    $@;
};

like($error, qr/Global symbol "\$strict_deref_array" requires explicit package name/,
    'strict-vars error survives a later syntax error after scalar dereference');
