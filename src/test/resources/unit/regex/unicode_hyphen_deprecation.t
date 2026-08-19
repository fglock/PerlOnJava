use strict;
use warnings;
use Test::More;

my $reason = 'Supplanted by Line_Break property values; see www.unicode.org/reports/tr14';
my @cases = (
    [q{qr/\p{Hyphen}/}, 'Hyphen', 'positive property outside a class'],
    [q{qr/\P{ishyphen}/}, 'ishyphen', 'negative property outside a class'],
    [q{qr/[\p{ _HYPHEN}]/}, '_HYPHEN', 'positive property inside a class'],
    [q{qr/[^\P{_ Is_Hyphen}]/}, '_ Is_Hyphen', 'negative property inside a class'],
);

for my $case (@cases) {
    my ($source, $spelling, $description) = @$case;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval "use warnings 'deprecated';\n#line 1 unicode_hyphen_deprecation.t\n$source";
    }
    is($@, '', "$description compiles");
    is(scalar @warnings, 1, "$description warns once");
    like($warnings[0],
        qr/^Use of '\Q$spelling\E' in \\p\{\} or \\P\{\} is deprecated because: \Q$reason\E at unicode_hyphen_deprecation\.t line 1\.\n\z/,
        "$description preserves spelling, text, and source line");

    @warnings = ();
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval "no warnings 'deprecated';\n#line 1 unicode_hyphen_deprecation.t\n$source";
    }
    is_deeply(\@warnings, [], "$description uses the deprecated category");
}

for my $spelling ('IsHyphen', 'Is_Hyphen') {
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        eval "use warnings 'deprecated';\n#line 1 unicode_hyphen_deprecation.t\nqr/\\p{$spelling}/";
    }
    is_deeply(\@warnings, [], "canonical $spelling alias is not deprecated");
}

my $interpolated_property = 'ASCII';
my $interpolated_regex = qr/\p{$interpolated_property}/;
like('A', $interpolated_regex,
    'read-only deprecation scan preserves property interpolation');
unlike("\x{100}", $interpolated_regex,
    'interpolated property retains its original membership');

done_testing;
