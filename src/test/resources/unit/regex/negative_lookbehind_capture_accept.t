use strict;
use warnings;
use Test::More;

my $accept = qr/(?<!([cd](*ACCEPT)|x)gggg)blrph/;

ok('cblrph' !~ $accept,
    'ACCEPT inside negative lookbehind rejects the outer match');
ok('dblrph' !~ $accept,
    'alternate ACCEPT path inside negative lookbehind rejects the outer match');
ok('qblrph' =~ $accept,
    'failed negative-lookbehind body permits the outer match');

ok('b' =~ /(?<!(a))b/, 'capture group is legal inside negative lookbehind');
ok(!defined($1), 'capture remains unset when negative lookbehind succeeds');
ok('ab' !~ /(?<!(a))b/, 'capturing negative lookbehind can reject a match');

done_testing;
