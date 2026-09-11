#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

{
    no warnings;
    sub CORE'print'foo { 43 }
    sub CORE'foo'bar { 43 }

    is CORE::print::foo, 43,
        'qualified CORE::print::foo is not a CORE::print call';
    is scalar eval q{CORE::foo'bar}, 43,
        'quote-qualified CORE package subroutine is not rejected as a keyword';
}

done_testing;
