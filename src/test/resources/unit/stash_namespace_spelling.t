use strict;
use warnings;
use Test::More tests => 4;
use B ();

no strict 'refs';
${"a'\0b"} = 'c';
is ${"a::\0b"}, 'c', q{legacy quote separator works before a NUL};

{
    package StashNamespaceEdge::;
    sub value { 1 }
}
ok StashNamespaceEdge::::value(), 'packages ending in :: retain their member separator';

our $forward;
{
    package stash_forward_origin;
    BEGIN { $main::forward = \&main::stash_forward_target }
}

my $cv = B::svref_2object($forward);
is $cv->STASH->NAME, 'stash_forward_origin',
    'a forward CV records the package where its reference was compiled';
like $cv->FILE, qr/stash_namespace_spelling/,
    'a forward CV records the source file where its reference was compiled';
