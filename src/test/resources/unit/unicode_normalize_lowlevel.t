use strict;
use warnings;
use Test::More tests => 6;
use Unicode::Normalize qw(decompose reorder compose);

is(decompose("\x{e9}"), "e\x{301}", 'canonical decomposition');
is(decompose("\x{fb01}"), "\x{fb01}", 'canonical decomposition keeps compatibility characters');
is(decompose("\x{fb01}", 1), 'fi', 'compatibility decomposition expands ligatures');

is(reorder("a\x{315}\x{300}"), "a\x{300}\x{315}", 'reorders combining marks');

is(compose("e\x{301}"), "\x{e9}", 'canonical composition');
is(compose('plain'), 'plain', 'composition preserves normalized text');
