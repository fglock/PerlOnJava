#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 5;

my $hash = eval q{{q,a'b,,'value'}};
is($@, '', 'quote with a comma delimiter parses inside a hash constructor');
is(ref($hash), 'HASH', 'evaluated constructor returns a hash reference');
is(ref($hash) eq 'HASH' ? $hash->{"a'b"} : undef, 'value',
    'quote with a comma delimiter preserves its key text');

my $block = eval q{{q,'bar',}};
is(ref($block), '', 'bareword q followed by a trailing comma remains a block');
is($block, "'bar'", 'the block control still returns its final expression');
