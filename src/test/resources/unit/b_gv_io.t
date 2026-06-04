use strict;
use warnings;
use Test::More;
use B ();

my $io = B::svref_2object(\*STDIN)->IO;

ok($io->can('IoFLAGS'), 'B::GV::IO returns an object with IoFLAGS');
ok(defined $io->IoFLAGS, 'IoFLAGS is defined');

done_testing;
