use strict;
use warnings;
use Test::More tests => 1;

{
    package StashCopiedGlobStringification;
    sub exported { 1 }
}

my %stash = %StashCopiedGlobStringification::;
is("$stash{exported}", '*StashCopiedGlobStringification::exported',
    'a glob copied from a package stash retains its name when stringified');
