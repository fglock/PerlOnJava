use strict;
use warnings;
use Test::More tests => 2;

eval 's//3}->{3/e';
like($@, qr/.+/, 'rejects trailing source in an evalled substitution replacement');

eval '1';
is($@, '', 'a successful later eval clears the substitution error');
