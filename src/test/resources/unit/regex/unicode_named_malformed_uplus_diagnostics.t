use strict;
use warnings;
use Test::More;

my @cases = (
    [string => q!"\N{U+XYZ}"!, 'string'],
    [regex => q!qr/\N{U+XYZ}/!, 'pattern'],
    [class => q!qr/[\N{U+XYZ}]/!, 'pattern'],
    [extended => q!no warnings 'experimental::regex_sets'; qr/(?[\N{U+XYZ}])/!,
        'pattern'],
);

for my $case (@cases) {
    my ($label, $source, $context) = @$case;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
        eval "#line 1 unicode_named_malformed_uplus_diagnostics.t\n$source";
    }
    my ($first_line) = split /\n/, $@;
    is($first_line,
        "Invalid hexadecimal number in \\N{U+...} at unicode_named_malformed_uplus_diagnostics.t line 1, within $context",
        "$label malformed U+ escape has the Perl diagnostic");
    is(scalar @warnings, 0,
        "$label malformed U+ escape emits no warning before the fatal");
}

done_testing;
