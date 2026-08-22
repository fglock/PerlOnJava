use strict;
use warnings;
use Test::More;

ok('a/b' =~ m Xa/bX, 'letter delimiter works when separated from m by whitespace');
is($&, 'a/b', 'letter-delimited match keeps slash literal');

my $letter_compile = eval q{ qr XabcX; };
ok(defined $letter_compile, 'letter-delimited qr compiles with required whitespace');
like('abc', $letter_compile, 'letter-delimited qr executes');

ok('abc' =~ m\abc\, 'backslash can delimit a match operator');
is($&, 'abc', 'backslash-delimited match preserves its body');

my $backslash_qr = qr\a/b\;
like('a/b', $backslash_qr, 'backslash-delimited qr keeps slash literal');

done_testing;
