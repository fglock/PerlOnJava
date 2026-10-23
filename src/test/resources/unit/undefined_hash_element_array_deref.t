use strict;
use warnings;
use Test::More tests => 1;

my %hash;
is(@{$hash{CLONES}}, 0,
    'array dereference of a missing hash element is empty in list argument context');
