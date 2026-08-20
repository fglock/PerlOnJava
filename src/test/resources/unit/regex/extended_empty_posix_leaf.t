use strict;
use warnings;
no warnings 'experimental::regex_sets';
no warnings qw(syntax regexp);
use Test::More tests => 12;

like(':', qr/(?[ [:] ])/, 'empty POSIX-looking leaf is an ordinary class');
unlike('a', qr/(?[ [:] ])/, 'empty POSIX-looking leaf contains only colon');
like('a', qr/(?[ (?# comment) [a] ])/, 'parenthesized comment is ignored');
unlike('b', qr/(?[ (?# comment) [a] ])/, 'comment contributes no members');

like(chr(ord('#') ^ 64), qr/(?[\c#])/, '\\c# matches its control character');
unlike('x', qr/(?[\c#])/, '\\c# excludes an ordinary character');
like("\c[", qr/(?[\c[])/, '\\c[ matches its control character');
unlike('x', qr/(?[\c[])/, '\\c[ excludes an ordinary character');
like("\c\\", qr/(?[\c\])/, '\\c\\ matches its control character');
unlike('x', qr/(?[\c\])/, '\\c\\ excludes an ordinary character');
like("\c]", qr/(?[\c]])/, '\\c] matches its control character');
unlike('x', qr/(?[\c]])/, '\\c] excludes an ordinary character');
