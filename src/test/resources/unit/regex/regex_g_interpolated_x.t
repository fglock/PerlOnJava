use strict;
use warnings;
use Test::More;

my $inner = qr/
 (%
  (?:{
    ([^;]*?)
    (?: ; ([^\}]*?) )?
  })
  ($|.)
 )
/xi;

my $message = '%{size;inch}n';
ok($message =~ /\G(.*?)$inner/gs,
    '\\G matches an interpolated /x qr pattern');
is($1, '', 'lazy prefix capture remains empty');
is($2, '%{size;inch}n', 'interpolated pattern preserves its full capture');
is($3, 'size', 'interpolated pattern preserves its named argument capture');
is($4, 'inch', 'interpolated pattern preserves its optional argument capture');
is($5, 'n', 'interpolated pattern preserves its conversion capture');

my $without_g = '%{size;inch}n';
ok($without_g =~ /(.*?)$inner/s,
    'interpolated /x qr pattern also matches without \\G');

local $| = 0;
is("$|", '0', 'special punctuation scalar still interpolates in ordinary strings');

done_testing;
