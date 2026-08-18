use strict;
use warnings;
use Test::More;

ok('AB' =~ /A(*PRUNE)B|AC/, 'straight PRUNE path can succeed');
ok('AC' !~ /A(*PRUNE)B|AC/, 'PRUNE cuts later alternative at same start');
ok('xxAC' !~ /A(*PRUNE)B|AC/, 'each later search start keeps its own cut');
ok('xca' =~ /a(*PRUNE)b|c/, 'search may succeed at an earlier alternative start');
ok('aa' !~ /a+(*PRUNE)a/, 'PRUNE discards greedy quantifier backtracking');
is(do { 'AAABC' =~ /A+?(*PRUNE)BC/; $& }, 'ABC',
    'lazy quantifier retries only at a later start after PRUNE');
ok('ac' !~ /(?:(?=a)a(*PRUNE)b|ac)/, 'nested noncapture PRUNE cuts its group');
ok('zab' =~ /z(?:a(*PRUNE)b|ac)/, 'nested PRUNE success preserves outer match');

done_testing;
