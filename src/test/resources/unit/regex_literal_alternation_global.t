use strict;
use warnings;
use Test::More tests => 7;

my $text = 'alpha:gamma:42:epsilon';
pos($text) = 0;
is($text =~ /42|gamma|epsilon/g, 1, 'first literal alternative match succeeds');
is($&, 'gamma', 'first match is the leftmost literal alternative');
is(pos($text), 11, 'first scalar global match publishes its end position');
is($text =~ /42|gamma|epsilon/g, 1, 'second literal alternative match succeeds');
is($&, '42', 'second match resumes at the next literal alternative');
is(pos($text), 14, 'second scalar global match advances position');

my $priority = 'ab';
is($priority =~ /a|ab/, 1, 'literal alternation preserves first-branch priority');
