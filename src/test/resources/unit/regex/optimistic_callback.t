use strict;
use warnings;
use Test::More;

my $count = 0;
local $^R = 'before';
ok('ab' =~ /a(*{ ++$count; 0 })b/,
    'standalone optimistic callback is zero width');
is($count, 1, 'standalone optimistic callback executes');
is($^R, 0, 'standalone optimistic callback updates $^R');

local $^R = 'condition-before';
ok('AB' =~ /(A)(?(*{ 1 })B|C)/,
    'true optimistic condition selects the yes branch');
is($1, 'A', 'true condition preserves ordinary capture state');
is($^R, 'condition-before', 'optimistic condition does not update $^R');
ok('AC' =~ /(A)(?(*{ 0 })B|C)/,
    'false optimistic condition selects the no branch');

my @seen;
ok('ab' =~ /(a)(*{
    push @seen, [defined $^N ? $^N : 'undef', defined $+ ? $+ : 'undef'];
})b/, 'standalone optimistic callback sees captures');
is_deeply($seen[0], ['a', 'a'],
    'optimistic capture view publishes $^N and $+');

my @nested;
ok('ab' =~ /((a)b)(*{
    push @nested, [defined $^N ? $^N : 'undef', defined $+ ? $+ : 'undef'];
})/, 'nested captures close before the optimistic callback');
is_deeply($nested[0], ['ab', 'a'], '$^N tracks close order independently of $+');

my @paths;
ok('ac' =~ /a(?:b(*{ push @paths, 'ab' })d|c(*{ push @paths, 'ac' }))/,
    'optimistic callback follows the reached alternative');
is_deeply(\@paths, ['ac'], 'unreached optimistic callbacks do not execute');

done_testing();
