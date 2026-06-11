use strict;
use warnings;
use Test::More tests => 5;

sub inner_entity_capture {
    my ($text) = @_;
    $text =~ /&(#?\w+);/;
    return $1;
}

my $value = q{&amp;-<![CDATA[&amp;]]>-&amp;};
ok($value =~ /<!\[CDATA\[(.*?)\]\]>/sg, 'outer CDATA match succeeds');
is($1, '&amp;', 'outer capture is set');
is(inner_entity_capture('&amp;'), 'amp', 'inner helper captures entity name');
is($1, '&amp;', 'outer capture survives subroutine call');

my $pos = pos $value;
my $rebuilt = substr($value, 0, $pos - length($1) - 12) . $1 . substr($value, $pos);
is($rebuilt, q{&amp;-&amp;-&amp;}, 'caller can use preserved capture after helper call');
