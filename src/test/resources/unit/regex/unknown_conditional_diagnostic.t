use strict;
use warnings;
use Test::More tests => 3;

for my $expression (q{qr/(?(x)y|x)/}, q{qr/(?(??{}))/}, q{qr/(?(?[]))/}) {
    my $compiled = eval "$expression; 1";
    ok(!$compiled && index($@, 'Unknown switch condition (?(...))') >= 0,
       'unknown conditional uses the Perl diagnostic');
}
