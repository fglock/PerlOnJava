#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

for my $case (
    [ q!"\x{"!, qr/^Missing right brace on \\x/, 'unclosed braced hex escape' ],
    [ q!"\o{"!, qr/^Missing right brace on \\o/, 'unclosed braced octal escape' ],
    [ q!"\Nfoo"!, qr/^Missing braces on \\N/, 'Unicode name escape requires braces' ],
) {
    my ($source, $expected, $name) = @$case;
    eval $source;
    like $@, $expected, $name;
}

done_testing;
