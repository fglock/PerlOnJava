use strict;
use warnings;
use Test::More tests => 14;

my $byte_nel = chr(0x85);
ok($byte_nel =~ /\A\R\z/, 'byte NEL is a line break');
ok($byte_nel !~ /\A\V\z/, 'byte NEL is not non-vertical whitespace');

my $unicode_nel = $byte_nel;
utf8::upgrade($unicode_nel);
ok($unicode_nel =~ /\A\R\z/, 'Unicode NEL is a line break');
ok($unicode_nel !~ /\A\V\z/, 'Unicode NEL is not non-vertical whitespace');
ok("\x{2028}" =~ /\A\R\z/, 'Unicode line separator is a line break');
ok("\x{2029}" =~ /\A\R\z/, 'Unicode paragraph separator is a line break');

my $mixed = "\r\n" . $byte_nel . "\r\n\n";
my ($capture) = "foo${mixed}bar" =~ /foo(\R+)bar/;
is($capture, $mixed, 'quantified line break consumes CRLF, NEL, CRLF, LF');

my ($breaks, $tail) = "${mixed}b" =~ /\A(\R+)(\V)\z/;
is($breaks, $mixed, 'quantified line break stops after the full sequence');
is($tail, 'b', 'non-vertical escape captures the following byte');

my ($nonvertical, $nel) = "o${byte_nel}" =~ /\A(\V)(\R)\z/;
is($nonvertical, 'o', 'non-vertical byte precedes byte NEL');
is($nel, $byte_nel, 'line-break capture consumes byte NEL after V');

ok("\r\n" =~ /\A\R\z/, 'CRLF remains one line-break atom');
ok("\r" =~ /\A\R\z/, 'CR remains a line break');
ok("\n" =~ /\A\R\z/, 'LF remains a line break');
