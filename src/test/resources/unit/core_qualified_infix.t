use strict;
use warnings;
use Test::More tests => 4;

my $dot = '.';
ok($dot CORE::eq '.', 'CORE-qualified string equality');
ok($dot CORE::ne '..', 'CORE-qualified string inequality');
ok(2 CORE::lt 3, 'CORE-qualified relational comparison');
ok((2 CORE::cmp 10) gt 0, 'CORE-qualified cmp keeps string semantics');
