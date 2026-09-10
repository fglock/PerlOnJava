#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

our %h;
eval 'keys(%h) .= "00"';
is $@, '', 'keys compound string assignment uses scalar context';

eval 'substr keys(%h), 0, = 3';
is $@, '', 'keys used as a substr lvalue argument uses scalar context';

done_testing;
