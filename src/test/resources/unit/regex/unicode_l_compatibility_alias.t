use strict;
use warnings;
use Test::More tests => 8;

my $upper = "A";
my $lower = "a";
my $title = chr(0x01C5);
my $ideograph = chr(0x3400);

ok($upper =~ /^\p{L_}$/, 'L_ matches an uppercase letter');
ok($lower =~ /^\p{L_}$/, 'L_ matches a lowercase letter');
ok($title =~ /^\p{L_}$/, 'L_ matches a titlecase letter');
ok($ideograph !~ /^\p{L_}$/, 'L_ rejects an uncased letter');

ok($upper !~ /^\P{L_}$/, 'negated L_ rejects an uppercase letter');
ok($lower !~ /^\P{L_}$/, 'negated L_ rejects a lowercase letter');
ok($title !~ /^\P{L_}$/, 'negated L_ rejects a titlecase letter');
ok($ideograph =~ /^\P{L_}$/, 'negated L_ matches an uncased letter');
