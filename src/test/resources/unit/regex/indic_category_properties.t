use strict;
use warnings;
use utf8;
no warnings 'experimental::regex_sets';
no warnings 'experimental::uniprop_wildcards';
use Test::More;

my $vowel_dependent = "\x{093E}";
my $bottom = "\x{093C}";

ok($vowel_dependent =~ /\p{InSC=Vowel_Dependent}/,
    'InSC short property matches outside a class');
ok('A' !~ /\p{InSC=Vowel_Dependent}/,
    'InSC excludes a default-value scalar');
ok($vowel_dependent =~ /[\p{Indic_Syllabic_Category=Vowel_Dependent}]/,
    'long InSC property matches in a standard class');
ok($vowel_dependent =~ /\p{indic syllabic-category = vowel dependent}/,
    'InSC accepts loose property and value spelling');
ok($bottom =~ /\p{InPC=Bottom}/,
    'InPC short property matches outside a class');
ok('A' !~ /\p{InPC=Bottom}/,
    'InPC excludes a default-value scalar');
ok($bottom =~ /[\p{Indic_Positional_Category=Bottom}]/,
    'long InPC property matches in a standard class');
ok($bottom =~ /\p{indic positional-category = bottom}/,
    'InPC accepts loose property and value spelling');

ok('A' =~ /\p{InSC=Other}/, 'InSC applies the Other missing default');
ok($vowel_dependent !~ /\p{InSC=Other}/,
    'explicit InSC range overrides the missing default');
ok('A' =~ /\p{InPC=NA}/, 'InPC accepts the short default value alias');
ok($bottom !~ /\p{InPC=NA}/,
    'explicit InPC range overrides the missing default');

ok($vowel_dependent =~ /(?[ \p{InSC=Vowel_Dependent} & \P{InPC=Bottom} ])/,
    'Indic properties compose in an extended class');
ok($bottom =~ /(?[ \p{InPC=Bottom} & \P{InSC=Vowel_Dependent} ])/,
    'extended class selects the positional property');
ok('A' !~ /(?[ \p{InSC=Vowel_Dependent} | \p{InPC=Bottom} ])/,
    'extended union excludes nonmembers');

ok($vowel_dependent =~ /\p{InSC=Vowel_Dependent}/i,
    'InSC resolves under ignore case');
ok('A' !~ /\p{InSC=Vowel_Dependent}/i,
    'ignore case does not add letters to InSC');
ok($bottom =~ /[\p{InPC=Bottom}]/i,
    'InPC resolves in an ignore-case standard class');
ok('A' !~ /[\p{InPC=Bottom}]/i,
    'ignore case does not add letters to InPC');
ok($vowel_dependent =~ /\p{InSC=:\AVowel_Dependent\z:}/,
    'InSC property-value wildcard selects a canonical value');
ok($bottom =~ /\p{InPC=:\ABottom\z:}/,
    'InPC property-value wildcard selects a canonical value');

{
    use bytes;
    ok('A' =~ /\p{InSC=Other}/, 'InSC default resolves in byte mode');
    ok("\xE9" =~ /[\p{InSC=Other}]/,
        'InSC default covers a high byte');
    ok('A' =~ /\p{InPC=NA}/, 'InPC default resolves in byte mode');
    ok("\xE9" =~ /[\p{InPC=NA}]/,
        'InPC default covers a high byte');
}

done_testing;
