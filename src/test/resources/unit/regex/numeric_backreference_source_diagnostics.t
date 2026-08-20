use strict;
use utf8;
use Test::More;

sub compile_error {
    my ($source) = @_;
    local $@;
    eval $source;
    return $@;
}

my @ascii = (
    [ q{/(x)\2/}, qr/Reference to nonexistent group.*m\/\(x\)\\2.*<-- HERE/ ],
    [ q{/\87/},   qr/Reference to nonexistent group.*m\/\\87.*<-- HERE/ ],
    [ q{/a\87/},  qr/Reference to nonexistent group.*m\/a\\87.*<-- HERE/ ],
    [ q{/a\97/},  qr/Reference to nonexistent group.*m\/a\\97.*<-- HERE/ ],
);

for my $case (@ascii) {
    like(compile_error($case->[0]), $case->[1],
        "numeric backreference marker for $case->[0]");
    like(compile_error("use re 'strict'; $case->[0]"), $case->[1],
        "strict numeric backreference marker for $case->[0]");
}

my $unicode = q{use utf8; /(ネ)\2ネ/};
my $unicode_expected = qr/Reference to nonexistent group.*m\/\(ネ\)\\2.*<-- HERE.*ネ/;
like(compile_error($unicode), $unicode_expected,
    'Unicode numeric backreference marker uses character offsets');
like(compile_error("use re 'strict'; $unicode"), $unicode_expected,
    'strict Unicode numeric backreference marker uses character offsets');

my $byte = chr 0xef;
my $byte_source = q{my $b = chr 0xef; qr/($b)\2$b/};
my $byte_expected = qr/Reference to nonexistent group.*\\2.*<-- HERE/;
like(compile_error($byte_source), $byte_expected,
    'byte-backed interpolated numeric backreference has a marker');
like(compile_error("use re 'strict'; $byte_source"), $byte_expected,
    'strict byte-backed numeric backreference has a marker');

done_testing;
