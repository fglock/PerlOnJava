use strict;
use warnings;
use utf8;
use B ();
use Test::More;

*b = \&B::svref_2object;
my $sub = do {
    package ᖟ레ￇ;
    \&{'ᖟ레ￇ'};
};
undef %ᖟ레ￇ::;

my $gv = b($sub)->GV;
is($gv->NAME, '__ANON__', 'CV name becomes anonymous after stash undef');
is($gv->STASH->NAME, '__ANON__', 'CV stash name becomes anonymous after stash undef');

my $detached = do {
    package sӥㄒ;
    \&{'sӥㄒ'};
};
my $stash_glob = delete $::{'sӥㄒ::'};
delete $$stash_glob{'sӥㄒ'};
my $detached_gv = B::svref_2object($detached)->GV;
is($detached_gv->STASH->NAME, '__ANON__',
    'a detached CV stash stays anonymous despite forward-reference metadata');

done_testing();
