use strict;
use warnings;
use Test::More tests => 8;

ok('AB' =~ /A(*PRUNE)B|AC/, 'PRUNE branch can succeed');
ok('AC' !~ /A(*PRUNE)B|AC/, 'PRUNE prevents a later alternative at the same start');
ok('xxAC' !~ /A(*PRUNE)B|AC/, 'unanchored PRUNE retries positions but still cuts each start');
ok('aa' !~ /a+(*PRUNE)a/, 'PRUNE discards quantifier backtracking');
ok('AAABC' =~ /A+?(*PRUNE)BC/, 'lazy quantifier can retry at a later start');

my $manifest_name = qr{ ' (*PRUNE) (?: [^'\\] ++ | \\ ['\\] ?+ ) ++ ' | \S ++ }x;
ok(q{'good name'} =~ /\A$manifest_name\z/, 'quoted manifest name matches');
ok(q{plain-name} =~ /\A$manifest_name\z/, 'unquoted manifest name matches');
ok(q{'unterminated} !~ /\A$manifest_name\z/, 'PRUNE rejects unterminated quoted manifest name');
