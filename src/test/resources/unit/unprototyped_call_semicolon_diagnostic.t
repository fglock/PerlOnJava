use strict;
use warnings;
use Test::More;

my $error = eval q{sub { f1(f2();) }};
like($@, qr/syntax error/, 'semicolon inside an unprototyped call is a syntax error');

done_testing;
