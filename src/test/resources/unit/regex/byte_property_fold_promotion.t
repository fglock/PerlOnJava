use strict;
use warnings;
use Test::More;

my $subject = "\xC0a";
utf8::downgrade($subject, 1);

ok($subject =~ /\xE0\pL/i,
    'literal byte followed by a property promotes case folding');
is($&, $subject,
    'literal property fold captures the complete byte subject');

my $literal = "\xE0";
utf8::downgrade($literal, 1);
my $pattern = qr/$literal\pL/i;
ok($subject =~ $pattern,
    'interpolated byte followed by a property promotes case folding');
is($&, $subject,
    'interpolated property fold captures the complete byte subject');

my $byte_upper = chr 0xC0;
utf8::downgrade($byte_upper, 1);
ok($byte_upper !~ /(?d:[]\P{Lowercase}_])/i,
    'leading literal close keeps a property inside its byte class');
ok($byte_upper !~ /(?d:[[:digit:]\P{Lowercase}_])/i,
    'POSIX nesting keeps a property inside its byte class');
ok($subject =~ /(?d:[[:digit:]]*)\xE0\pL/i,
    'a property after a POSIX class promotes the following byte fold');
is($&, $subject,
    'the POSIX class scanner returns to top level after its outer close');

no warnings 'experimental::regex_sets';
my $byte_lower = chr 0xE0;
utf8::downgrade($byte_lower, 1);
ok($byte_lower =~
        /(?d:(?[ ([\x{e0}] + \p{Hex_Digit}) - \p{Hex_Digit} ]))/i,
    'nested extended classes retain their surviving byte literal');
ok($byte_upper =~
        /(?d:(?[ ([\x{e0}] + \p{Hex_Digit}) - \p{Hex_Digit} ]))/i,
    'nested extended classes retain byte fold provenance');
ok('[A' =~ /^\[\p{Lowercase}$/i,
    'an escaped opening bracket does not hide a top-level property');

done_testing;
