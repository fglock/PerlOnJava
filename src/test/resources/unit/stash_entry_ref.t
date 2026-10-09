use strict;
use warnings;
no warnings 'once';
use Test::More;

# ref() on a stash entry depends on the package and the kind of sub:
#  - a defined sub in main:: is a bare CODE entry;
#  - a prototyped constant in main:: is a scalar reference entry;
#  - a forward declaration in main:: is a typeglob entry (ref is "");
#  - a sub or constant in any other package is a typeglob entry (ref is "");
#  - a constant.pm proxy is a scalar reference entry in every package.

package Stash::Plain;
sub PLAIN () { 1 }
sub named { 2 }

package Stash::Proxy;
use constant PROXY => 2;

package main;
sub main_named { 3 }
sub main_const () { 4 }
sub main_forward;

is ref($Stash::Plain::{PLAIN}), '',
    'a prototyped constant sub outside main:: is a typeglob stash entry';
is ref($Stash::Plain::{named}), '',
    'a sub outside main:: is a typeglob stash entry';
is ref($Stash::Proxy::{PROXY}), 'SCALAR',
    'a constant.pm proxy is a scalar reference stash entry';
is ref($main::{main_named}), 'CODE',
    'a defined sub in main:: is a CODE stash entry';
is ref($main::{main_const}), 'SCALAR',
    'a prototyped constant sub in main:: is a scalar reference stash entry';
is ref($main::{main_forward}), '',
    'a forward declaration in main:: is a typeglob stash entry';

is Stash::Plain::PLAIN(), 1, 'the prototyped constant sub still returns its value';
is Stash::Proxy::PROXY(), 2, 'the constant.pm proxy still returns its value';
is main_named(), 3, 'the main:: sub still runs';

done_testing;
