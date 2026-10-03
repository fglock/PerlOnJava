#!/usr/bin/env perl

use strict;
use warnings;
if ($ENV{PERLONJAVA_DEEP_EVAL_CHILD}) {
    our $x = 1;
    my $expression = '1';
    $expression = "(\$x ? 1 : $expression)" for 1 .. 20_000;
    $expression = "\$x = $expression";
    eval $expression;
    exit(defined($@) && $@ eq '' && $x == 1 ? 0 : 1);
}

require Test::More;
Test::More->import(tests => 1);

my $child_status;
{
    local $ENV{JPERL_OPTS} = '-Xss256m';
    local $ENV{PERLONJAVA_DEEP_EVAL_CHILD} = 1;
    $child_status = system($^X, __FILE__);
}

is($child_status, 0,
    'deep eval executes and clears $@ with the stack budget used by core tests');
