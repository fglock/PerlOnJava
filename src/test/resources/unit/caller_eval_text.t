use strict;
use warnings;
use Test::More tests => 3;

my $source = '(caller 0)[6]';
is(eval $source, $source, 'caller returns eval STRING source');

my $heredoc = "<<END;\nvalue\nEND\n(caller 0)[6]";
is(eval $heredoc, $heredoc, 'caller retains here-doc eval source');

my $quote_like = "s//<<END/e;\nvalue\nEND\n(caller 0)[6]";
is(eval $quote_like, $quote_like, 'caller retains quote-like here-doc eval source');
