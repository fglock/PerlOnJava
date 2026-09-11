#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

eval 'for my Missing::Loop::Type $item (1) {}';
like $@, qr/^No such class Missing::Loop::Type at /,
    'unknown type in a lexical for declaration is rejected during compilation';

done_testing;
