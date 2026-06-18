use strict;
use warnings;
use Test::More tests => 3;

my $s = 'abc';
my $count = ($s =~ s{(.*?)(x)?}{'<' . (defined($1) ? $1 : 'undef') . '>'}ge);

is $s, '<><a><><b><><c><>', 'global substitution retries non-empty match after zero-length match';
is $count, 7, 'zero-length and retry replacements are both counted';

my $pattern = 'trailing space';
$pattern =~ s{
    (.*?)
    (
        \\.
        |
        \*
        |
        \?
    )?
}{
    quotemeta $1;
}gsex;

is $pattern, 'trailing\ space', 'nullable s///g replacement sees skipped characters';
