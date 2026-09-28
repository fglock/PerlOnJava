use strict;
use warnings;
use Test::More;

my @letters = split // => 'abc';
is_deeply(\@letters, [qw(a b c)], 'split accepts a fat comma after its pattern');

@letters=split//=>"def";
is_deeply(\@letters, [qw(d e f)], 'split accepts a fat comma without surrounding whitespace');

done_testing();
