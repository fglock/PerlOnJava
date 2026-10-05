use strict;
use warnings;
use Test::More;

sub reported_call_line {
    return (caller 0)[2];
}

my $line = reported_call_line
sub {};

is($line, __LINE__ - 3,
    'a bare multi-line call with an anonymous-sub argument reports the call line');

done_testing;
