use strict;

BEGIN {
    eval <<'PERL';
sub quietly_leave_outer_block {
    local $^W = 0;
    last OUTER;
}
PERL
    die $@ if $@;
}

use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    OUTER: {
        quietly_leave_outer_block();
        fail('last OUTER should leave the caller block');
    }
}

is scalar @warnings, 0,
    'local $^W = 0 suppresses the warning for a non-local last';

done_testing;
