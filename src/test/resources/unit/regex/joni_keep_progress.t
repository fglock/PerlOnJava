use strict;
use warnings;
use Test::More tests => 7;

my $x = "abc.def.ghi.jkl";
$x =~ s/.*\K\..*//;
is($x, "abc.def.ghi", 'greedy keep substitution');

$x = "one two three four";
$x =~ s/o+ \Kthree//g;
is($x, "one two  four", 'global keep substitution');

$x = "abcde";
$x =~ s/(.)\K/$1/g;
is($x, "aabbccddee", 'consuming zero-width keep substitutions do not skip');

$x = 'abc';
my @seen;
while ($x =~ /(.)\K/g) {
    push @seen, [$1, $&, pos($x)];
}
is_deeply(\@seen, [['a', '', 1], ['b', '', 2], ['c', '', 3]],
    'global keep advances by consumed input while exposing an empty match');

$x = 'abc';
for my $expected (['a', 1], ['b', 2], ['c', 3]) {
    ok($x =~ /(.)\K/g && $1 eq $expected->[0] && pos($x) == $expected->[1],
        "scalar global keep reaches $expected->[0]");
}
