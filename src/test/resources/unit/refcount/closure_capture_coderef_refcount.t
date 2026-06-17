use strict;
use warnings;
use Test::More;
use B qw(svref_2object);

my $__dummy;

sub make_wrapper {
    my ($done, $fail) = @_;
    return sub {
        $done->();
        $fail->();
    };
}

{
    my $done = sub { $__dummy++ };
    my $fail = sub { $__dummy-- };
    my $wrapper = make_wrapper($done, $fail);

    is(svref_2object($done)->REFCNT, 2, 'closure capture counts as a coderef owner');
    is(svref_2object($fail)->REFCNT, 2, 'closure capture counts as a second coderef owner');
    $wrapper->();
}

done_testing;
