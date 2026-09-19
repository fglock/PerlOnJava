use strict;
use warnings;
use utf8;
use Test::More;

no warnings 'utf8';
my $surrogate = "\x{d800}\x{ffff}";
$surrogate =~ tr/\0/A/c;
is($surrogate, 'AA', 'complement transliterates surrogate scalars as single characters');

my $extended = "A\x{ffff}B";
$extended =~ tr/\x{ffff}/\x{1ffff}/;
is($extended, "A\x{1ffff}B", 'extended Unicode code points survive transliteration');

my $beyond_unicode = 'cb';
$beyond_unicode =~ tr{aabc}{d\x{d0000}};
is($beyond_unicode, "\x{d0000}\x{d0000}", 'replacement scalars above Unicode remain logical characters');

my $named = "\x{cb}";
$named =~ tr[\N{U+00CB}\N{U+00EB}\N{U+2010}][\N{U+0401}\N{U+0451}\-];
is($named, "\x{401}", 'Unicode names are expanded in transliteration lists');
done_testing();
