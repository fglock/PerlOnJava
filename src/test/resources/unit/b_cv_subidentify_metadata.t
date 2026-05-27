use strict;
use warnings;
use Test::More;
use B ();
use Test2::Util::Sub qw(sub_info);

sub xander;
my $decl = B::svref_2object(\&xander);
is($decl->GV->STASH->NAME, 'main', 'forward declaration keeps stash');
is($decl->GV->NAME, 'xander', 'forward declaration keeps GV name');
ok(!$decl->START->isa('B::COP'), 'forward declaration has no start COP');

sub deux ();
sub quatre () { 4 }

my $const = B::CVf_CONST();
ok(!(B::svref_2object(\&deux)->CvFLAGS & $const), 'prototype-only declaration is not constant');
is(B::svref_2object(\&quatre)->CvFLAGS & $const, $const, 'empty-prototype literal sub is constant');

my $info = sub_info(\&quatre);
is($info->{name}, 'quatre', 'B op walk terminates for Test2::Util::Sub');

done_testing;
