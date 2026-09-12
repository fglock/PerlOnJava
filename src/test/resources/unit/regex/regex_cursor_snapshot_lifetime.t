use strict;
use warnings;
use Test::More;

my $first = 'ab-42';
ok($first =~ /(ab)-(42)/, 'first subject matches');
is_deeply([ $1, $2, @-, @+ ], [ 'ab', '42', 0, 0, 3, 5, 2, 5 ],
    'first match publishes complete capture state');

my $second = 'xy-99';
ok($second =~ /(xy)-(99)/, 'second distinct subject matches');
is_deeply([ $1, $2, @-, @+ ], [ 'xy', '99', 0, 0, 3, 5, 2, 5 ],
    'second match replaces every visible capture and offset');

ok(!('no match' =~ /(never)-(matches)/), 'later failed match fails');
is_deeply([ $1, $2, @-, @+ ], [ 'xy', '99', 0, 0, 3, 5, 2, 5 ],
    'failed match preserves the immutable state from the prior success');

my $global = 'a1 b2';
my @pairs = ($global =~ /([a-z])(\d)/g);
is_deeply(\@pairs, [ qw(a 1 b 2) ], 'list global match consumes every cursor result');
is_deeply([ $1, $2, @-, @+ ], [ 'b', '2', 3, 3, 4, 5, 4, 5 ],
    'final global result remains published after cursor iteration');

done_testing;
