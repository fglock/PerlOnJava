use strict;
use warnings;
use Test::More;

my $text = 'alpha:42:gamma:epsilon';
my @seen;
while ($text =~ /(?:(42)|(gamma)|(epsilon))/g) {
    push @seen, [ $&, defined $1 ? $1 : '', defined $2 ? $2 : '',
        defined $3 ? $3 : '', $-[0], $+[0], pos($text) ];
}

is_deeply(
    \@seen,
    [
        [ '42', '42', '', '', 6, 8, 8 ],
        [ 'gamma', '', 'gamma', '', 9, 14, 14 ],
        [ 'epsilon', '', '', 'epsilon', 15, 22, 22 ],
    ],
    'ASCII global matches preserve captures, offsets, and pos');

my $wide = "A\x{1F642}B";
ok($wide =~ /(B)/, 'Unicode fallback matches after a supplementary character');
is_deeply([ $-[0], $+[0], $-[1], $+[1] ], [ 2, 3, 2, 3 ],
    'Unicode fallback retains Perl character offsets');

done_testing;
