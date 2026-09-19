use strict;
use warnings;
use Test::More;

my @values = qw(red blue);
my @seen;

foreach my $value (@values) {
    goto $value . '';

red:
    push @seen, $value;
    next;

blue:
    push @seen, $value;
    next;
}

is_deeply(\@seen, [qw(red blue)],
    'a computed goto within an entered foreach body is permitted');

done_testing;
