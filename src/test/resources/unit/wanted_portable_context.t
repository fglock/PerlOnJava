use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Wanted; Wanted->import(qw(want rreturn)); 1 }
        or plan skip_all => 'Wanted required';
}

sub detected_context {
    return Wanted::context();
}

sub returned_value {
    rreturn('first', 'last');
}

sub double_returned_value {
    rreturn('returned from caller');
    die 'rreturn continued in its caller';
}

is(scalar(detected_context()), 'SCALAR', 'detects scalar context');
my @contexts = detected_context();
is_deeply(\@contexts, ['LIST'], 'detects list context');
is(scalar(returned_value()), 'last', 'rreturn preserves scalar result');
my @values = returned_value();
is_deeply(\@values, ['first', 'last'], 'rreturn preserves list result');
is(double_returned_value(), 'returned from caller', 'rreturn returns from its caller');

done_testing;
