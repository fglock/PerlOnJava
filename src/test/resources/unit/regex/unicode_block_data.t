use strict;
use warnings;
use Test::More;
use Unicode::UCD ();

no warnings 'experimental::uniprop_wildcards';

sub accepts_property {
    my ($property, $cp, $name) = @_;
    my $re = eval 'qr/\\A\\p{' . $property . '}\\z/u';
    my $error = $@;
    my $matched = $re ? eval { chr($cp) =~ $re ? 1 : 0 } : 0;
    $error ||= $@;
    ok(!$error && $matched, $name) or diag("property=$property error=$error");
}

sub rejects_property {
    my ($property, $name) = @_;
    my $re = eval 'qr/\\A\\p{' . $property . '}\\z/u';
    my $error = $@;
    eval { "A" =~ $re } if $re && !$error;
    $error ||= $@;
    ok($error, $name) or diag("unexpectedly accepted property=$property");
}

TODO: {
    local $TODO = 'Block resolver wiring is a separate Phase 36 slice';

    accepts_property('Block=Basic_Latin', 0x41, 'Block long property/value');
    accepts_property('Blk=ASCII', 0x41, 'Blk and ASCII official aliases');
    accepts_property('Blk=Latin1Sup', 0x80, 'official compact block value alias');
    accepts_property('b_l-k=b-a s_i c_latin', 0x41,
        'loose matching in name and value');
    accepts_property('Block: BasicLatin', 0x41, 'colon separator and loose value');

    accepts_property('InBasicLatin', 0x41, 'InBlockName shortcut');
    accepts_property('INBasicLatin', 0x41, 'single-form In name is loosely matched');
    accepts_property('isBasicLatin', 0x41, 'single-form Is name is loosely matched');
    accepts_property('IsBlock=Basic_Latin', 0x41, 'exact Is compound prefix');
    accepts_property('Is_Block=Basic_Latin', 0x41, 'exact Is_ compound prefix');
    rejects_property('isBlock=Basic_Latin', 'lowercase is is not compound Is prefix');
    rejects_property('ISBlock=Basic_Latin', 'uppercase IS is not compound Is prefix');

    accepts_property('Block=Greek_And_Coptic', 0x378,
        'unassigned code point remains in block');
    accepts_property('InGreek', 0x378, 'InGreek resolves to block');
    accepts_property('General_Category=Cn', 0x378, 'same code point is unassigned');
    accepts_property('Greek', 0x1F00, 'Greek resolves to script extensions');
    accepts_property('IsGreek', 0x1F00, 'IsGreek resolves to script extensions');

    accepts_property('Block=No_Block', 0x2FE0, 'gap has default No_Block value');
    accepts_property('Blk=NB', 0x2FE0, 'NB is official No_Block alias');
    accepts_property('InNB', 0x2FE0, 'InNB shortcut');
    accepts_property('Block=Arabic_Presentation_Forms_A', 0xFDD0,
        'noncharacter U+FDD0 retains enclosing block');
    accepts_property('Noncharacter_Code_Point', 0xFDD0, 'U+FDD0 is a noncharacter');
    accepts_property('Block=Specials', 0xFFFE, 'U+FFFE retains Specials block');

    accepts_property('Block=#\\ABasic_Latin\\z#', 0x41, 'anchored wildcard value');
    accepts_property('Block=#\\A(?:Basic_Latin|Greek_And_Coptic)\\z#', 0x378,
        'wildcard value alternation');
    rejects_property('Block=Basic*', 'bare glob is not a wildcard value');
    rejects_property('Block=#Old I.*#', 'star quantifier forbidden in wildcard');
    rejects_property('Block=#\\ANever_A_Block\\z#',
        'wildcard matching no value is rejected');
    rejects_property('Is_Block=#\\ABasic_Latin\\z#',
        'wildcard unavailable with Is_ prefix');

    rejects_property('Block', 'Block needs a value');
    rejects_property('Blk', 'Blk needs a value');
    rejects_property('Blocks=Basic_Latin', 'invalid property alias');
    rejects_property('Block=Basic_Lati', 'invalid value alias');
    rejects_property('InDefinitelyNotABlock', 'unknown In property');

    SKIP: {
        skip 'local standard Perl uses Unicode ' . Unicode::UCD::UnicodeVersion(), 2
            if Unicode::UCD::UnicodeVersion() lt '17.0.0';
        accepts_property('Block=Sidetic', 0x10940, 'Unicode 17 Sidetic sentinel');
        accepts_property('Block=Tolong_Siki', 0x11DB0,
            'Unicode 17 Tolong Siki sentinel');
    }
}

done_testing;
