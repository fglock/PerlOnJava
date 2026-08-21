use strict;
use warnings;
use Test::More;

{
    package DoBlockFreshResult;
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

sub observe_after_argument {
    is($DoBlockFreshResult::destroyed, 1,
        'fresh do-block result permits transient lexical destruction at scope exit');
}

observe_after_argument(do { 1; !!(my $object = bless [], 'DoBlockFreshResult') });

done_testing;
