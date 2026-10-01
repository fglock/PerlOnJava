use strict;
use warnings;
use utf8;
use Test::More;

my $byte_subject = "F\x{f8}o";
utf8::downgrade($byte_subject) or die 'subject should fit in byte string';
my $word = qr/\w+/;

ok($byte_subject =~ /^$word/, 'byte word class matches the initial ASCII word character');
is($&, 'F', 'byte word class does not consume non-ASCII byte characters');

my $wide_subject = "F\x{f8}o";
utf8::upgrade($wide_subject);
ok($wide_subject =~ /^$word/, 'upgraded subject uses Unicode word semantics');
is($&, $wide_subject, 'upgraded subject matches the complete Unicode word');

my $unicode_word = qr/\w+/u;
ok($byte_subject =~ /^$unicode_word/, 'explicit Unicode word class matches byte input');
is($&, $byte_subject, 'explicit Unicode word class upgrades and matches the complete input');

done_testing;
