use strict;
use warnings;
use Test::More;

my @cases = (
    [string => q!"\N{REGEX IMPLEMENTATION UNKNOWN NAME"!,
        'Missing right brace on \N{}', 'string'],
    [regex => q!qr/\N{REGEX IMPLEMENTATION UNKNOWN NAME/!,
        'Missing right brace on \N{} or unescaped left brace after \N', 'pattern'],
    [class => q!qr/[\N{REGEX IMPLEMENTATION UNKNOWN NAME]/!,
        'Missing right brace on \N{} or unescaped left brace after \N', 'pattern'],
    [extended => q!no warnings 'experimental::regex_sets'; qr/(?[\N{REGEX IMPLEMENTATION UNKNOWN NAME])/!,
        'Missing right brace on \N{} or unescaped left brace after \N', 'pattern'],
);

for my $case (@cases) {
    my ($label, $source, $message, $context) = @$case;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
        eval "#line 1 unicode_named_missing_brace_diagnostics.t\n$source";
    }
    my ($first_line) = split /\n/, $@;
    is($first_line,
        "$message at unicode_named_missing_brace_diagnostics.t line 1, within $context",
        "$label unterminated named escape has the Perl diagnostic");
    is(scalar @warnings, 0,
        "$label unterminated named escape emits no warning before the fatal");
}

done_testing;
