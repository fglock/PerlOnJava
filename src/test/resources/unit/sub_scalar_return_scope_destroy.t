use strict;
use warnings;
use Test::More;

{
    package ScalarReturnScopeDestroy;
    our $destroyed = 0;
    sub DESTROY { ++$destroyed }
}

sub flag_from_temporary_guard {
    !!(my $guard = bless [], 'ScalarReturnScopeDestroy');
}

sub observe_destroy {
    is($ScalarReturnScopeDestroy::destroyed, 1,
        'a scalar-returning sub releases its local guard before the next call');
}

observe_destroy(flag_from_temporary_guard());

done_testing;
