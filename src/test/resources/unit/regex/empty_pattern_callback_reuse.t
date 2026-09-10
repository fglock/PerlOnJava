use strict;
use warnings;
use re 'eval';
use Test::More;

my $callback_pattern = qr/(?{ 1 })x/;
ok 'x' =~ $callback_pattern, 'callback-bearing pattern matches';

my $result = eval q{'x' =~ s//y/r};
is $@, '', 'empty-pattern reuse recompiles without an error';
is $result, 'y', 'empty-pattern reuse retains the prior callback regex';

done_testing;
