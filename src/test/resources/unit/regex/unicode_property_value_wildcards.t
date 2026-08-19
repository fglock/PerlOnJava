use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

sub compile_property {
    my ($marker, $property) = @_;
    my $pattern = eval 'qr/\\A\\' . $marker . '{' . $property . '}\\z/u';
    return ($pattern, $@);
}

my @samples = map { chr($_) } (
    0x0000, 0x000A, 0x000D, 0x0020, 0x0021, 0x0030, 0x0041, 0x005F,
    0x0061, 0x00A0, 0x0300, 0x05D0, 0x0600, 0x0903, 0x200D, 0x2029,
    0x30A2, 0xE000, 0x1F1E6, 0x1F3FB, 0x1F600, 0xE0100,
);

sub same_membership {
    my ($wild_property, $exact_property, $label) = @_;
    my ($wild, $wild_error) = compile_property('p', $wild_property);
    ok(defined $wild, "$label wildcard compiles") or diag($wild_error);
    my ($exact, $exact_error) = compile_property('p', $exact_property);
    ok(defined $exact, "$label exact control compiles") or diag($exact_error);
    return unless defined $wild && defined $exact;
    ok(!grep({ (defined($_ =~ $wild) ? !!($_ =~ $wild) : 0)
                    != (defined($_ =~ $exact) ? !!($_ =~ $exact) : 0) } @samples),
        "$label wildcard equals exact assignment");
}

my %binary_values = (
    patsyn                    => [qw(n y)],
    patternsyntax             => [qw(no yes)],
    patws                     => [qw(n y)],
    patternwhitespace         => [qw(no yes)],
    pcm                       => [qw(n y)],
    prependedconcatenationmark => [qw(no yes)],
    qmark                     => [qw(n y)],
    quotationmark             => [qw(no yes)],
    radical                   => [qw(f no t yes)],
    ri                        => [qw(n y)],
    regionalindicator         => [qw(no yes)],
    sd                        => [qw(n y)],
    softdotted                => [qw(no yes)],
    sentenceterminal          => [qw(no yes)],
    sterm                     => [qw(n y)],
    space                     => [qw(f t)],
    term                      => [qw(n y)],
    terminalpunctuation       => [qw(no yes)],
    uideo                     => [qw(n y)],
    unifiedideograph          => [qw(no yes)],
    upper                     => [qw(n y)],
    uppercase                 => [qw(no yes)],
    variationselector         => [qw(no yes)],
    vs                        => [qw(n y)],
    whitespace                => [qw(no yes)],
    wspace                    => [qw(n y)],
    xidc                      => [qw(n y)],
    xidcontinue               => [qw(no yes)],
    xids                      => [qw(n y)],
    xidstart                  => [qw(no yes)],
);

for my $property (sort keys %binary_values) {
    for my $value (@{$binary_values{$property}}) {
        same_membership(
            "$property=:\\A$value\\z:", "$property=$value",
            "binary $property $value");
    }
}

my %enumerated_values = (
    wb => [qw(cr dq eb ebg em ex extend fo gaz hl ka le lf mb ml mn nl nu ri sq wsegspace xx zwj)],
    wordbreak => [qw(aletter cr doublequote ebase ebasegaz emodifier extend
        extendnumlet format glueafterzwj hebrewletter katakana lf midletter
        midnum midnumlet newline numeric other regionalindicator singlequote
        wsegspace zwj)],
    sb => [qw(at cl cr ex fo le lf lo nu sc se sp st up xx)],
    sentencebreak => [qw(aterm close cr extend format lf lower numeric oletter
        other scontinue sep sp sterm upper)],
    vo => [qw(r tr tu u)],
    verticalorientation => [qw(rotated transformedrotated transformedupright upright)],
);

for my $property (sort keys %enumerated_values) {
    for my $value (@{$enumerated_values{$property}}) {
        same_membership(
            "$property=:\\A$value\\z:", "$property=$value",
            "enumerated $property $value");
    }
}

for my $control (
    ['P', 'WB=:\\Acr\\z:', 'WB=CR', 'Word_Break outer negation'],
    ['P', 'SB=:\\Aaterm\\z:', 'SB=AT', 'Sentence_Break outer negation'],
    ['P', 'VO=:\\Ar\\z:', 'VO=R', 'Vertical_Orientation outer negation'],
    ['P', 'Upper=:\\Ay\\z:', 'Upper=Y', 'binary outer negation'],
) {
    my ($marker, $wild_property, $exact_property, $label) = @$control;
    my ($wild, $wild_error) = compile_property($marker, $wild_property);
    ok(defined $wild, "$label wildcard compiles") or diag($wild_error);
    my ($exact, $exact_error) = compile_property($marker, $exact_property);
    ok(defined $exact, "$label exact control compiles") or diag($exact_error);
    next unless defined $wild && defined $exact;
    ok(!grep({ !!($_ =~ $wild) != !!($_ =~ $exact) } @samples),
        "$label is preserved");
}

{
    no warnings 'non_unicode';
    my $wide = chr(0x110000);
    my ($wild_rotated, $error) = compile_property('p', 'VO=:\\Arotated\\z:');
    ok(defined $wild_rotated, 'wide Vertical_Orientation wildcard compiles')
        or diag($error);
    ok($wide =~ $wild_rotated,
        'wide code point retains default Rotated membership')
        if defined $wild_rotated;
}

for my $unknown (
    'WB=:\\Ano_such_value\\z:',
    'SB=:\\Ano_such_value\\z:',
    'VO=:\\Ano_such_value\\z:',
    'Upper=:\\Ano_such_value\\z:',
) {
    my ($pattern, $error) = compile_property('p', $unknown);
    ok(!defined($pattern) && length($error), "$unknown remains rejected");
}

done_testing;
