use v5.26;
use strict;
use warnings;
use Test::More tests => 2;

sub outer {
    my ($value) = @_;

    my sub middle {
        my ($current) = @_;
        my sub inner { $current }
        inner();
    }

    middle($value);
}

is outer('first'), 'first', 'nested lexical sub captures first invocation';
is outer('second'), 'second', 'nested lexical sub gets a fresh closure per invocation';
