use strict;
use warnings;
use Test::More tests => 6;

is('xxabcdefyy' =~ /abcdef/, 1, 'six-byte literal matches');
ok('xxabcdefyy' !~ /abcdeg/, 'six-byte literal rejects a mismatch');
is('xxabcdefgyy' =~ /abcdefg/, 1, 'seven-byte literal matches');
ok('xxabcdefgyy' !~ /abcdefh/, 'seven-byte literal rejects a mismatch');

my $text = 'abcdefgabcdefg';
my @matches = $text =~ /abcdefg/g;
is_deeply(\@matches, [qw(abcdefg abcdefg)], 'seven-byte literal preserves list /g results');
is(pos($text), undef, 'list /g resets pos after exact-literal matches');
