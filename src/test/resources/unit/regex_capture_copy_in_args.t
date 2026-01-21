use strict;
use warnings;
use Test::More tests => 2;

sub capture_arg {
    my ($x) = @_;
    # Run another regex to clobber $1
    "abc" =~ /(a)/;
    return $x;
}

"Q 12" =~ /^Q\s*(\d+)/;
is($1, '12', 'sanity: $1 is 12 after match');

is(capture_arg($1), '12', 'captured $1 passed into sub survives later regex (must be copied)');
