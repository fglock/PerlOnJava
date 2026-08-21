use strict;
use warnings;
use Test::More;

ok('ab[1c' =~ m'ab\[(??{1})c',
    'one backslash leaves a callback outside an escaped opening bracket');

ok('ab\\[1c' =~ m'ab\\\[(??{1})c',
    'three backslashes leave a callback outside an escaped opening bracket');

ok("ab\\;c" =~ m'ab\\[(??{1;})]c',
    'two backslashes leave callback-looking text inside a class');

ok("ab\\\\1c" =~ m'ab\\\\[(??{1})]c',
    'four backslashes preserve even parity before a character class');

ok(']1' =~ m'[a\]](??{1})',
    'escaped class close is followed by an active callback after the real close');

ok('ab2' =~ m'ab[(?{1\](?{2]',
    'callback-looking text after an escaped close remains inside a class');

ok('ab[1c' =~ m'ab\[(??{1}) c'x,
    'escaped opening bracket preserves callback extraction under /x');

ok('a#' =~ m{
    a\#(??{q{}})
}x,
    'escaped hash under /x does not turn the following callback into a comment');

ok('a' =~ m{
    a # (??{ die "comment callback executed" })
}x,
    'an unescaped hash under /x still hides callback-looking comment text');

done_testing;
