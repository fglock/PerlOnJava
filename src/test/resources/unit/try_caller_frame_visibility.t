use strict;
use warnings;
use feature 'try';
use Test::More tests => 1;

no warnings 'experimental::try';
my $try_caller;
sub caller_try_inner { $try_caller = sprintf '%s (%s line %d)', (caller 1)[3,1,2] }
sub caller_try_outer {
    try { caller_try_inner() }
    catch ($error) { }
}
my $try_line = __LINE__ + 1;
caller_try_outer();
is(
    $try_caller,
    "main::caller_try_outer ($0 line $try_line)",
    'try block does not replace the caller call site',
);

done_testing;
