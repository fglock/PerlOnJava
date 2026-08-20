use strict;
use warnings;
use Test::More;

sub compile_case {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $regex = eval $source;
    return ($regex, $@, \@warnings);
}

sub succeeds_with_warning {
    my ($name, $source, $warning) = @_;
    my ($regex, $error, $warnings) = compile_case($source);
    ok($regex, "$name compiles");
    is($error, '', "$name is not fatal");
    is(scalar @$warnings, 1, "$name emits one warning");
    like($warnings->[0], $warning, "$name warning text and marker");
}

sub dies_without_warning {
    my ($name, $source, $error_pattern) = @_;
    my ($regex, $error, $warnings) = compile_case($source);
    ok(!$regex, "$name does not compile");
    like($error, $error_pattern, "$name fatal text and marker");
    is(scalar @$warnings, 0, "$name emits no warning before dying");
}

succeeds_with_warning(
    'non-strict braced octal', q{qr/\o{789}/},
    qr/^Non-octal character '8'.*m\/\\o\{789\} <-- HERE /s);
succeeds_with_warning(
    'non-strict braced hex', q{qr/\x{defg}/},
    qr/^Non-hex character 'g'.*m\/\\x\{defg\} <-- HERE /s);
succeeds_with_warning(
    'non-strict short octal', q{qr/\08/},
    qr/^Non-octal character '8'.*Resolved as "\\0008".*m\/\\08 <-- HERE /s);
succeeds_with_warning(
    'non-strict class short octal', q{qr/[\08]/},
    qr/^Non-octal character '8'.*m\/\[\\08 <-- HERE \]/s);

dies_without_warning(
    'strict braced octal',
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\o{789}/},
    qr/^Non-octal character.*m\/\\o\{78 <-- HERE 9\}/s);
dies_without_warning(
    'strict class braced hex',
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/[\x{defg}]/},
    qr/^Non-hex character.*m\/\[\\x\{defg <-- HERE \}\]/s);
dies_without_warning(
    'extended-class braced octal',
    q{no warnings 'experimental::regex_sets'; qr/(?[ \o{1038} ])/},
    qr/^Non-octal character.*m\/\(\?\[ \\o\{1038 <-- HERE \} \]\)/s);
dies_without_warning(
    'extended-class braced hex',
    q{no warnings 'experimental::regex_sets'; qr/(?[ \x{defg} ])/},
    qr/^Non-hex character.*m\/\(\?\[ \\x\{defg <-- HERE \} \]\)/s);
dies_without_warning(
    'strict long unbraced hex',
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\xABC/},
    qr/^Use \\x\{\.\.\.\} for more than two hex characters.*m\/\\xABC <-- HERE /s);
dies_without_warning(
    'strict empty braced hex',
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\x{}/},
    qr/^Empty \\x\{\}.*m\/\\x\{\} <-- HERE /s);
dies_without_warning(
    'strict terminal hex escape',
    q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\x{100}\x/},
    qr/^Empty \\x.*m\/\\x\{100\}\\x <-- HERE /s);

for my $case (
    ['valid strict octal', q{no warnings 'experimental::re_strict'; use re 'strict'; qr/[\000]/}],
    ['valid strict braced octal', q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\o{707}/}],
    ['valid strict braced hex', q{no warnings 'experimental::re_strict'; use re 'strict'; qr/\x{def}/}],
) {
    my ($name, $source) = @$case;
    my ($regex, $error, $warnings) = compile_case($source);
    ok($regex, "$name compiles");
    is($error, '', "$name is not fatal");
    is(scalar @$warnings, 0, "$name is quiet");
}

done_testing;
