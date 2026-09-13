#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# A normal eval of an upgraded string has Unicode source semantics even when
# the enclosing file did not enable `use utf8`.
no strict 'vars';
my $unicode_identifier = "\x{104B0}";

eval "\$$unicode_identifier";
is $@, '', 'Unicode eval accepts a scalar identifier';

eval "*$unicode_identifier";
is $@, '', 'Unicode eval accepts a typeglob identifier';

done_testing;
