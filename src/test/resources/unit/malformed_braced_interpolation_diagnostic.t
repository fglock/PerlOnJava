use strict;
use warnings;
use Test::More;

eval 'm(@{if(0){sub d{]]])}return';
my $error = $@;
like($error, qr/^syntax error at \(eval \d+\) line 1, near "\{\]"/,
    'malformed braced interpolation retains its syntax context');

eval '${';
like($@, qr/syntax error at \(eval \d+\) line 1/,
    'unterminated braced interpolation retains a syntax error diagnostic');

done_testing;
