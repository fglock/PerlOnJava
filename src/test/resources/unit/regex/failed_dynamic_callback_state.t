use strict;
use warnings;
use re 'eval';
use Test::More tests => 4;

my $nested = qr/(a)(?{ 1 })/;
'old' =~ /(old)/;

ok(!('a' =~ /^(??{$nested})(?!)$/),
    'outer failure unwinds a successful dynamic callback pattern');
is($1, 'old', 'failed dynamic callback preserves the preceding numbered capture');
is($^N, 'old', 'one-level dynamic callback failure preserves the last closed capture');
is($+, 'old', 'one-level dynamic callback failure preserves the last participating capture');
