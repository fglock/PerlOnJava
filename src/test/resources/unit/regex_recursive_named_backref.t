use strict;
use warnings;
use Test::More tests => 3;

my $parenthesized = qr/( \( (?: [^()]++ | (?-1) )*+ \) )/x;
my $option = qr{
    \G \s* (?<key>(?>(?:[^,=*/]+)))
    (?: \*= (?<value>.*)
      | /= (?<delim>.) (?<value>.*?) \g{delim} (?=,|\z) ,*
      | (?: = (?<value> (?:[^,()]++ | ${parenthesized})*+ ) )? ,* )
}x;

ok('name/=/two words/' =~ $option,
    'named brace backreference works in a recursive Joni pattern');
is($+{value}, 'two words', 'named brace backreference captures its value');
ok('name=outer(inner)' =~ $option,
    'interpolated relative recursion remains available');
