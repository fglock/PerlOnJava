use strict;
use warnings;
use feature qw(lexical_subs state);
no warnings 'experimental::lexical_subs';
use Test::More;

sub LexicalSortPackage::_cmp { $b cmp $a }

{
    package LexicalSortPackage;
    our sub _cmp;
    package main;

    is join(' ', sort _cmp split //, 'oursub'), 'u u s r o b',
        'sort resolves an our-sub comparator in its declaring package';
}

{
    state sub _cmp { $b cmp $a }
    is join(' ', sort _cmp split //, 'lexsub'), 'x u s l e b',
        'sort accepts a state-sub comparator code reference';
}

{
    my sub _cmp { $b cmp $a }
    is join(' ', sort _cmp split //, 'lexsub'), 'x u s l e b',
        'sort accepts a my-sub comparator code reference';
}

done_testing;
