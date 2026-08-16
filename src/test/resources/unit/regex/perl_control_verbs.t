use strict;
use warnings;
use Test::More tests => 8;

ok('ac' !~ /a(*PRUNE)b|ac/,
    'PRUNE rejects later alternatives at the same start');
ok('ac' =~ /a(*PRUNE)b|c/,
    'PRUNE permits a match at a later search start');

ok('aaab' =~ /a(*SKIP)(*FAIL)|b/,
    'SKIP resumes searching at the position where it executed');
is($&, 'b', 'SKIP exposes the match found after the skipped prefix');

ok('ab' =~ /a(?:(?=b)(*THEN)c|b)/,
    'THEN enters the nearest alternative after a zero-width condition');
ok('abe' !~ /a(?:b(*THEN)c|bd)|abe/,
    'THEN does not escape to an outer alternative');

ok('ac' !~ /a(*COMMIT)b|ac/,
    'COMMIT rejects later alternatives at the same start');
ok('ab' !~ /a(*COMMIT)c|b/,
    'COMMIT prevents retry at a later search start');
