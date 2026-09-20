use strict;
use warnings;
use Test::More;

eval q!eval"${sub{sub{//]]]"}}!;

like($@,
    qr/syntax error at \(eval 1\) line 1, near "\/\/\]"/,
    'malformed regex inside interpolation reports the quote-like construct');

done_testing;
