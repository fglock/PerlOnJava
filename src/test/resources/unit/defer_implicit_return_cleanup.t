use strict;
use warnings;
use Test::More;
use feature 'defer';
no warnings 'experimental::defer';

our $defer_ran = 0;
sub named_sub_ending_in_defer {
    defer { $defer_ran++ }
}

my $result = named_sub_ending_in_defer();
ok(!defined($result), 'a named subroutine ending in defer returns undef');
is($defer_ran, 1, 'a named subroutine ending in defer runs its deferred block');

done_testing;
