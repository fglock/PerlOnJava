use strict;
use warnings;
use re 'eval';
use Test::More tests => 12;

my $wide = "\x{3FE}";
my $bytes = "\xCF\xBE";
ok(utf8::is_utf8($wide), 'wide scalar retains Unicode provenance');
ok(!utf8::is_utf8($bytes), 'encoded octets retain byte provenance');

ok("$wide$wide" =~ /^$wide(??{$wide})\z/,
    'Unicode postponed source matches a Unicode subject');
ok("$bytes$wide" =~ /^$bytes(??{$wide})\z/,
    'byte prefix and Unicode postponed source match a Unicode suffix');
ok("$bytes$wide" !~ /^$bytes(??{$bytes})\z/,
    'byte postponed source does not consume a Unicode suffix');
ok("$wide$wide" !~ /^$wide(??{$bytes})\z/,
    'byte postponed source does not consume a Unicode subject');
ok("$bytes$bytes" =~ /^$bytes(??{$bytes})\z/,
    'byte postponed source matches a byte subject');
ok("$bytes$bytes" !~ /^$bytes(??{$wide})\z/,
    'Unicode postponed source does not consume encoded octets');

my $wide_ascii = 'a';
utf8::upgrade($wide_ascii);
my $wide_latin1 = "\x{E9}";
utf8::upgrade($wide_latin1);
my $byte_latin1 = "\xE9";
ok(utf8::is_utf8($wide_ascii), 'ASCII control has Unicode provenance');
ok('a' =~ /^(??{$wide_ascii})\z/,
    'Unicode-provenance ASCII postponed source matches a byte subject');
ok(utf8::is_utf8($wide_latin1) && !utf8::is_utf8($byte_latin1),
    'Latin-1 control operands retain distinct provenance');
ok($byte_latin1 =~ /^(??{$wide_latin1})\z/,
    'Unicode-provenance Latin-1 postponed source matches the same byte value');
