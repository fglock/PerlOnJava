use strict;
use warnings;
use Test::More tests => 2;
use B ();

my $code = sub { 1 };
my $cv = B::svref_2object($code);

isa_ok($cv, 'B::CV');
is($cv->GV->NAME, '__ANON__',
    'B introspects an anonymous CV without requiring Sub::Name');
