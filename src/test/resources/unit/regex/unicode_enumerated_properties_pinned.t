use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

my $probe = eval q{qr/\p{bpt=:\A(?:Open|o)\z:}/};
if (!defined $probe) {
    plan skip_all => 'pinned enumerated Unicode properties await resolver integration';
}

my @values = (
    ['bpt','o','Open',0x0028], ['bpt','c','Close',0x0029], ['bpt','n','None',0x0041],
    ['InCB','Linker','Linker',0x094D], ['InCB','Consonant','Consonant',0x0915],
    ['InCB','Extend','Extend',0x0300], ['InCB','None','None',0x0041],
    ['jt','C','Join_Causing',0x0640], ['jt','D','Dual_Joining',0x0620],
    ['jt','L','Left_Joining',0xA872], ['jt','R','Right_Joining',0x0622],
    ['jt','T','Transparent',0x00AD], ['jt','U','Non_Joining',0x0041],
    ['nt','De','Decimal',0x0030], ['nt','Di','Digit',0x00B2],
    ['nt','Nu','Numeric',0x00BC], ['nt','None','None',0x0041],
    ['vo','R','Rotated',0x0041], ['vo','U','Upright',0x00A7],
    ['vo','Tr','Transformed_Rotated',0x3014], ['vo','Tu','Transformed_Upright',0x3001],
);
for my $v (@values) {
    my ($property,$short,$long,$code)=@$v;
    my $short_re=eval "qr/\\p{$property=$short}/";
    ok(defined $short_re,"$property=$short compiles") or diag($@);
    like(chr($code),$short_re,"$property=$short matches representative");
    my $long_re=eval "qr/\\p{$property=$long}/";
    ok(defined $long_re,"$property=$long compiles") or diag($@);
    like(chr($code),$long_re,"$property=$long matches representative");
}

for my $case (
    [q{qr/\p{bidi paired-bracket type = o-p e_n}/},0x0028,'loose bpt'],
    [q{qr/\p{Indic Conjunct-Break=Linker}/},0x094D,'loose InCB'],
    [q{qr/\p{Joining_Type:Dual_Joining}/},0x0620,'jt colon'],
    [q{qr/\p{Numeric_Type=Decimal}/},0x0030,'nt equals'],
    [q{qr/\p{Vertical_Orientation:Upright}/},0x00A7,'vo colon'],
    [q{qr/\p{Is_bpt=Open}/},0x0028,'Is bpt equals'],
    [q{qr/\p{Is_nt:Decimal}/},0x0030,'Is nt colon'],
) {
    my ($source,$code,$description)=@$case; my $re=eval $source;
    ok(defined $re,"$description compiles") or diag($@); like(chr($code),$re,"$description matches");
}

for my $case (
    [q{qr/\p{bpt=:\A(?:Open|o)\z:}/},0x0028,'bpt wildcard'],
    [q{qr/\p{InCB=:\A(?:Linker|Extend)\z:}/},0x094D,'InCB wildcard'],
    [q{qr/\p{jt=:\A(?:D|Dual_Joining)\z:}/},0x0620,'jt wildcard'],
    [q{qr/\p{nt=:\A(?:De|Decimal)\z:}/},0x0030,'nt wildcard'],
    [q{qr/\p{vo=:\A(?:U|Upright)\z:}/},0x00A7,'vo wildcard'],
) {
    my ($source,$code,$description)=@$case; my $re=eval $source;
    ok(defined $re,"$description compiles") or diag($@); like(chr($code),$re,"$description matches");
}

for my $case (
    [q{qr/\p{Is_bpt=:\AOpen\z:}/},qr/Can't find Unicode property definition/,'Is-prefixed wildcard'],
    [q{qr/\p{bpt=}/},qr/Unicode property wildcard not terminated/,'missing value'],
    [q{qr/\p{vo=:\A.*\z:}/},qr/quantifier '\*' is not allowed/i,'star wildcard'],
    [q{qr/\p{nt=Open}/},qr/Can't find Unicode property definition/,'cross-property value'],
) {
    my ($source,$error,$description)=@$case; my $re=eval $source;
    ok(!defined $re,"$description is rejected"); like($@,$error,"$description diagnostic");
}

done_testing();
