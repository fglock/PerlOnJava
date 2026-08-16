use strict;
use warnings;
use Test::More;

my $evaluations = 0;
my $pattern = 'ab|a';
my $dynamic = qr/^(??{ ++$evaluations; $pattern })b$/;
is($evaluations, 0, 'dynamic expression does not run while qr is constructed');
ok('ab' =~ $dynamic, 'nested alternatives backtrack into the outer suffix');
is($evaluations, 1, 'dynamic expression runs once when its program is entered');

ok('ab' =~ /^(??{ 'ab|a' })b$/,
    'constant alternatives retain nested-program grouping');

my $returned_qr = qr/(b)/;
ok('ab' =~ /^a(??{ $returned_qr })$/, 'dynamic expression accepts a qr value');
ok(!defined $1, 'captures in a returned qr do not enter the outer namespace');

ok('abc' =~ /^(a)(??{ '(b)' })(c)$/, 'constant capture-bearing program matches');
is($1, 'a', 'outer first capture keeps its number');
is($2, 'c', 'dynamic captures do not consume outer capture numbers');

our @events;
my $callback_qr = qr/ab(?{ push @events, 'inner' })|a/;
ok('ab' =~ /^(??{ push @events, 'dynamic'; $callback_qr })b$/,
    'returned callback regex can yield another alternative');
is_deeply(\@events, [qw(dynamic inner)],
    'dynamic and nested callback side effects occur at match time');

our $recursive;
$recursive = qr{ \( (?: [^()]+ | (??{ $recursive }) )* \) }x;
ok('(a(b)c)' =~ /^$recursive$/, 'self-referential dynamic qr matches nested input');

done_testing();
