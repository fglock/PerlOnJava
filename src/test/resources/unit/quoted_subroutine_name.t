#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

{
    no warnings qw(syntax deprecated);
    sub 'Hello'_he_said (_);
}

is prototype('Hello::_he_said'), '_',
    'leading quote declares the intended package subroutine';

done_testing;
