use strict;
use warnings;
use Test::More tests => 3;

$CORE::GLOBAL::{lock} = [];
eval 'no warnings; lock';
like $@, qr/^Not enough arguments for lock/,
    'a non-CODE CORE::GLOBAL lock slot does not override the keyword';

$CORE::GLOBAL::{readline} = [];
eval '<STDOUT> if 0';
is $@, '', 'a non-CODE CORE::GLOBAL readline slot does not override diamond syntax';

eval 'require IO::Handle; 1';
is $@, '', 'a later lexical-handle diamond remains valid after the non-CODE stash entry';
