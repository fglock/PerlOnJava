use strict;
use warnings;
use Test::More;
use JSON::PP;

my $json = JSON::PP->new;
is($json->decode('{"a":"x"}')->{a}, 'x',
   'JSON::PP decodes a string in a labeled outer-loop parser path');

done_testing;
