use strict;
use warnings;
use Test::More;

our ($REGMARK, $REGERROR);
$REGMARK = undef;
$REGERROR = undef;

ok('ab' =~ /a(*ACCEPT:accepted)z/, 'named ACCEPT ends the current match');
is($&, 'a', 'named ACCEPT preserves its match boundary');
is($REGMARK, 'accepted', 'named ACCEPT publishes its argument');
is($REGERROR, '', 'named ACCEPT clears REGERROR');

ok('ac' !~ /a(*FAIL:blocked)c/, 'named FAIL rejects the match');
is($REGMARK, '', 'named FAIL clears REGMARK after failure');
is($REGERROR, 'blocked', 'named FAIL publishes its argument');

ok('ac' !~ /a(*F:short)c/, 'named F shorthand rejects the match');
is($REGERROR, 'short', 'named F publishes its argument');

ok('ab' =~ /a(*FAIL:first)b|ab/, 'named FAIL can backtrack to a successful branch');
is($REGMARK, '1', 'successful controlled match without a mark publishes true');
is($REGERROR, '', 'success clears a backtracked named FAIL argument');

ok('ab' =~ /(?=(a(*ACCEPT:inner)z))ab/,
    'named ACCEPT respects a nested assertion boundary');
is($1, 'a', 'nested named ACCEPT closes its active capture');
is($REGMARK, 'inner', 'nested named ACCEPT publishes its argument');

$REGMARK = 'sentinel mark';
$REGERROR = 'sentinel error';
ok('x' !~ /z|a(*FAIL:unreached)/, 'pattern can fail before reaching named FAIL');
is($REGMARK, 'sentinel mark', 'unreached control verb leaves REGMARK untouched on failure');
is($REGERROR, 'sentinel error', 'unreached control verb leaves REGERROR untouched on failure');

done_testing;
