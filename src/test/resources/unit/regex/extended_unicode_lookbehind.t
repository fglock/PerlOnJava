use strict;
use warnings;
use utf8;
use Test::More tests => 7;

ok("a\x{301}" =~ /^\X$/,
    'extended grapheme cluster includes a combining mark');
ok("\x{1F469}\x{200D}\x{1F4BB}" =~ /^\X$/,
    'extended grapheme cluster includes an emoji ZWJ sequence');
ok("\x{1F600}" =~ /^\p{Extended_Pictographic}$/,
    'extended pictographic Unicode property is available');
ok("\x{B7}" =~ /^\p{Script_Extensions=Greek}$/,
    'script extensions Unicode property is available');

ok('bcd' =~ /(?<=a|bc)d/,
    'different-length lookbehind alternatives match');
ok('aaab' =~ /(?<=a{1,3})b/,
    'bounded variable-length positive lookbehind matches');
ok('zaab' !~ /(?<!a{1,3})b/,
    'bounded variable-length negative lookbehind rejects a matching prefix');
