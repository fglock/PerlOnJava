#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

{
    package EvalAfterStashDelete::Pkg;
    sub doomed { 42 }
}

my $compiled_before_delete = sub { EvalAfterStashDelete::Pkg::doomed() };

delete $EvalAfterStashDelete::Pkg::{doomed};

ok(!EvalAfterStashDelete::Pkg->can('doomed'),
    'stash delete removes method lookup entry');

my $old_value = eval { $compiled_before_delete->() };
is($old_value, 42, 'call site compiled before stash delete keeps its CV');

my $new_value = eval q{EvalAfterStashDelete::Pkg::doomed()};
like($@, qr/Undefined subroutine &EvalAfterStashDelete::Pkg::doomed called/,
    'eval compiled after stash delete does not resurrect pinned CV');

my $new_coderef = eval q{\&EvalAfterStashDelete::Pkg::doomed};
ok(!defined &$new_coderef,
    'new code reference after stash delete is an undefined slot');
