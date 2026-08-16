use strict;
use warnings;
use Test::More tests => 2;

sub named_sub { 'original' }

my $saved = \&named_sub;
undef *named_sub;

ok(!defined(&named_sub), 'named stash entry was removed');
is($saved->(), 'original', 'saved coderef retains the compiled CV');
