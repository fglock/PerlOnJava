use strict;
use warnings;
use Test::More tests => 4;

my $text = '1234';
for (substr($text, 1, 2)) {
    $text = '5678';
    is("$_", '67', 'live substr string read follows parent replacement');
    is(0 + $_, 67, 'live substr numeric read follows parent replacement');
    ok(defined $_, 'live substr remains defined after defined parent replacement');
    $text = undef;
    ok(!defined $_, 'live substr becomes undef with an undef parent');
}
