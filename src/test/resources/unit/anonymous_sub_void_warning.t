#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
  eval q{{ use warnings; sub { 1 }; 1; }};
}

like(join('', @warnings), qr/Useless use of anonymous subroutine in void context/,
    'anonymous subroutine in void context warns');

done_testing;
