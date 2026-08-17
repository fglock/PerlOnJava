use strict;
use warnings;
use Test::More;

my $quoted = q('first' 'second');
ok($quoted =~ /'([^']+?)'/,
    'lazy negated class finds the leftmost quoted token');
is($1, 'first',
    'lazy excluded-delimiter capture is the first payload');
is($&, q('first'),
    'lazy excluded-delimiter full match is leftmost and shortest');

my $shared = 'abXcdX';
ok($shared =~ /\A([^Z]+?)X/,
    'lazy class that can consume delimiter still matches');
is($1, 'ab',
    'lazy class gives the first delimiter to the suffix');
is($&, 'abX',
    'lazy result stops at the first delimiter');

is('[value]' =~ /\A\[([^\]]+?)\]\z/ ? $1 : undef, 'value',
    'escaped closing bracket works in class and terminator');
is('<value>' =~ /\A<([^\x{3e}]+?)\x{3e}\z/ ? $1 : undef, 'value',
    'hex-escaped delimiter works in class and terminator');
is('<value>' =~ /\A<([^=>]+?)>\z/ ? $1 : undef, 'value',
    'class range that excludes delimiter preserves capture');

my $long = q(') . ('a' x 20_000) . q(');
ok($long =~ /\A'([^']+?)'\z/,
    'long lazy negated class reaches its excluded terminator');
is(length($1), 20_000,
    'long lazy capture is complete');

done_testing;
