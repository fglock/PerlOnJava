use strict;
use warnings;
use Test::More tests => 4;
use File::Temp;

my ($template, $args) = File::Temp::_parse_args();

is(ref($template), 'ARRAY', 'list assignment preserves the empty template array reference');
is_deeply($template, [], 'template reference remains empty');
is(ref($args), 'HASH', 'list assignment preserves the argument hash reference');
is_deeply($args, {}, 'argument reference remains empty');
