#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;
no strict;

eval q{package TypedBlockField; %FIELDS; my TypedBlockField $r; ${$r}{key};};
like($@, qr/No such class field "key" in variable \$r of type TypedBlockField/,
    'block hash dereference rejects an unknown field after referencing %FIELDS');

eval q{package TypedSliceField; %FIELDS; my TypedSliceField $f; @$f{"a"};};
like($@, qr/No such class field "a" in variable \$f of type TypedSliceField/,
    'one-key hash slice rejects an unknown field after referencing %FIELDS');
