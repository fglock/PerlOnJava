use strict;
use warnings;
use Test::More;

my $literal = 'abcdefghijklmnop';
my $subject = 'xx' . $literal;

ok($subject =~ /$literal/, 'long literal matches after a rejected prefix');
my $match_offset = $-[0];
is($match_offset, 2, 'long literal publishes its first match offset');
ok('xxabcdefghijklmnoq' !~ /$literal/, 'last-byte mismatch does not match');
ok('xxabcdefghijklm' !~ /$literal/, 'short subject does not match');

done_testing;
