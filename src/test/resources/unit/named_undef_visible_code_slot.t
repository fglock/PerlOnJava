use strict;
use warnings;
use Test::More tests => 4;

package NamedUndefVisibleCodeSlot;

sub target { 'old' }

package main;

my $saved = \&NamedUndefVisibleCodeSlot::target;
my $undef_named = sub { undef &NamedUndefVisibleCodeSlot::target };

{
    no warnings 'redefine';
    *NamedUndefVisibleCodeSlot::target = sub { 'replacement' };
}
$undef_named->();
ok(!defined(&NamedUndefVisibleCodeSlot::target),
    'compiled named undef clears the visible replacement CODE slot');
is($saved->(), 'old', 'named undef does not invalidate a saved old CV');

delete $NamedUndefVisibleCodeSlot::{target};
$undef_named->();
ok(!exists($NamedUndefVisibleCodeSlot::{target}),
    'named undef does not recreate an absent stash slot');

{
    no warnings 'redefine';
    *NamedUndefVisibleCodeSlot::target = sub { 'later' };
}
$undef_named->();
ok(!defined(&NamedUndefVisibleCodeSlot::target),
    'compiled named undef follows a later redefinition');
