#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 3;

push my(@my_array), 1;
is_deeply(\@my_array, [1], 'push accepts parenthesized my array declaration');

push our(@our_array), 2;
is_deeply(\@our_array, [2], 'push accepts parenthesized our array declaration');

our @local_array = (3);
{
    push local(@local_array), 4;
    is_deeply(\@local_array, [4], 'push accepts parenthesized local array declaration');
}
