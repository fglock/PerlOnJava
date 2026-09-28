use strict;
use warnings;
use Test::More;

sub reported_call_line {
    return (caller 0)[2];
}

my $line = reported_call_line
sub {};

is($line, __LINE__ - 2,
    'a bare multi-line call with an anonymous-sub argument reports the argument line');

done_testing;
