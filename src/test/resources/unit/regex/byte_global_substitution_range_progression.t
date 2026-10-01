use strict;
use warnings;
use Test::More;

my $subject = pack('C*', 0x46, 0xf8, 0x6f, 0x28, 0x3f, 0x3c, 0x61, 0x3e);
utf8::downgrade($subject, 1) or die 'fixture should be byte backed';

my $count = $subject =~ s/([^\x20-\x7e])/sprintf('\\x{%02X}', ord($1))/ge;
is($count, 1, 'global substitution replaces only the non-ASCII byte');
is($subject, 'F\\x{F8}o(?<a>',
    'global substitution leaves the following ASCII regex syntax unchanged');

done_testing;
