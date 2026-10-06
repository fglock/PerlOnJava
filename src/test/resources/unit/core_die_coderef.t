use strict;
use warnings;
use Test::More tests => 3;

my $die = \&CORE::die;
eval { $die->('code reference failure') };
like($@, qr/^code reference failure at .* line \d+\.\n$/,
    'CORE::die code reference preserves the caller location');

#line 3 frob



sub dier { &CORE::die(@_) } # 6
eval { dier('mapped failure') };
is($@, "mapped failure at frob line 6.\n",
    'CORE::die code reference preserves Perl #line location');

my $evalbytes = \&CORE::evalbytes;
eval { $evalbytes->('1', '2') };
like($@, qr/^Too many arguments for eval "string"/,
     'CORE::evalbytes code reference uses the Perl arity diagnostic');
