use strict;
use warnings;
use Test::More;

'abc' =~ /(a)(b)/;
is($1, 'a', 'first capture is published');
is($2, 'b', 'second capture is published');
is_deeply(\@-, [0, 0, 1], 'first match start offsets are published');
is_deeply(\@+, [2, 1, 2], 'first match end offsets are published');

'z' =~ /z/;
ok(!defined $1, 'a successful capture-free match clears prior capture $1');
ok(!defined $2, 'a successful capture-free match clears prior capture $2');
is_deeply(\@-, [0], 'capture-free match publishes only whole-match start');
is_deeply(\@+, [1], 'capture-free match publishes only whole-match end');

'xy' =~ /(x)(y)/;
my @starts = @-;
my @ends = @+;
'q' =~ /q/;
is_deeply(\@starts, [0, 0, 1], 'captured offsets remain ordinary Perl values');
is_deeply(\@ends, [2, 1, 2], 'captured end offsets remain ordinary Perl values');

done_testing;
