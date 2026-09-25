use strict;
use warnings;
use Test::More tests => 2;
no strict 'vars';

$::{poj_array_constant} = [1, 2, 3];
is join('-', eval 'poj_array_constant()'), '1-2-3',
    'array-reference stash entry is callable as a constant';
is ref $::{poj_array_constant}, 'ARRAY',
    'calling through eval preserves the stash array-reference view';
