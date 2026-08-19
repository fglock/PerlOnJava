use strict;
use warnings;
use Test::More tests => 10;

my @lexical;
ok('abc' !~ /^a(?{ push @lexical, 1 })b(?{ push @lexical, 2 })$/,
   'path with two plain callbacks fails');
is_deeply(\@lexical, [], 'both lexical mutations unwind in reverse order');

our @package;
ok('abc' !~ /^a(?{ push @package, 1 })b(?{ push @package, 2 })$/,
   'package mutation path with two callbacks fails');
is_deeply(\@package, [], 'both package mutations unwind in reverse order');

my @dynamic;
ok('abc' !~ /^a(??{ push @dynamic, 1; 'b' })$/,
   'dynamic callback nested program fails');
is_deeply(\@dynamic, [1], 'dynamic expression mutation survives failure');

my @nested;
ok('abc' !~ /^(??{ qr{a(?{ push @nested, 1 })b(?{ push @nested, 2 })} })$/,
   'returned program containing plain callbacks fails');
is_deeply(\@nested, [1, 2],
          'plain callback mutations inside a dynamic program survive failure');

my @condition;
ok('ac' !~ /^a(?(?{ push @condition, 1; 1 })b|c)$/,
   'conditional callback chooses a failing branch');
is_deeply(\@condition, [1], 'conditional expression mutation survives failure');
