use strict;
use warnings;
use Test::More tests => 5;

no warnings 'non_unicode';

my $above_unicode = chr(0x110000);
ok($above_unicode =~ /\p{Unassigned}/, 'Unassigned includes above-Unicode scalars');
ok($above_unicode =~ /\p{Cn}/, 'Cn includes above-Unicode scalars');
ok($above_unicode =~ /\p{General_Category=Cn}/,
   'General_Category=Cn includes above-Unicode scalars');
ok($above_unicode =~ /\p{Category=Unassigned}/,
   'Category=Unassigned includes above-Unicode scalars');
ok($above_unicode !~ /\P{Unassigned}/,
   'negated Unassigned excludes above-Unicode scalars');
