use strict;
use warnings;
use Test::More;

# These feature-free patterns are eligible for an execution-cursor snapshot:
# they have no numbered/named captures, callouts, or deferred properties.
my $first = 'alpha:42';
ok($first =~ /(?:42|gamma|epsilon)/, 'first zero-capture subject matches');
is_deeply([ $&, $`, $', @-, @+ ], [ '42', 'alpha:', '', 6, 8 ],
    'first match publishes complete overall-match state');

my $second = 'xxgamma!';
ok($second =~ /(?:42|gamma|epsilon)/, 'second distinct subject matches');
is_deeply([ $&, $`, $', @-, @+ ], [ 'gamma', 'xx', '!', 2, 7 ],
    'second subject replaces the published overall-match state');

ok(!('no tokens' =~ /(?:42|gamma|epsilon)/), 'later zero-capture match fails');
is_deeply([ $&, $`, $', @-, @+ ], [ 'gamma', 'xx', '!', 2, 7 ],
    'failed match preserves the snapshot from the preceding success');

my $global = '42:gamma:epsilon';
my @matches = ($global =~ /(?:42|gamma|epsilon)/g);
is_deeply(\@matches, [ qw(42 gamma epsilon) ], 'list global match consumes each result');
is_deeply([ $&, $`, $', @-, @+ ], [ 'epsilon', '42:gamma:', '', 9, 16 ],
    'terminal global probe retains the final published zero-capture state');

done_testing;
