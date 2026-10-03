use strict;
use warnings;
use Test::More;

plan skip_all => 'requires a terminal on STDIN' unless -t STDIN;

my $result = qx{"$^X" -e 'print(-t STDIN ? "tty" : "notty")'};
is($result, 'tty', 'qx children inherit terminal STDIN');

done_testing;
