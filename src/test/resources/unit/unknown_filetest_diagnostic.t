#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

eval q{ my $x = -F 1; };
like $@, qr/(?i:syntax|parse) error .* near "F 1"/,
    'unknown one-letter filetest reports a syntax error';

eval q{ sub F { 42 } -F 1 };
is $@, '', 'defined F remains a unary-minus subroutine call';

done_testing;
