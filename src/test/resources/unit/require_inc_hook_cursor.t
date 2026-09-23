use strict;
use warnings;
use Test::More;

my @saved_inc = @INC;

@INC = (
    sub {
        @INC = qw(a b);
        undef $INC;
        return;
    },
    'z',
);

eval { require 'Frobnitz.pm' };
like(
    $@,
    qr/\@INC entries checked: CODE\(0x[0-9a-f]+\) a b/,
    'clearing $INC restarts a replaced @INC search from slot zero',
);

@INC = @saved_inc;

done_testing;
