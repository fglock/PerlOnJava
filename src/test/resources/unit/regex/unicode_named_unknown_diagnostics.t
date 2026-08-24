use strict;
use warnings;
use Test::More;

my @contexts = (
    [string => sub { qq!"$_[0]"! }, 'string'],
    [regex => sub { qq!qr/$_[0]/! }, 'pattern'],
    [class => sub { qq!qr/[$_[0]]/! }, 'pattern'],
    [extended => sub {
        qq!no warnings 'experimental::regex_sets'; qr/(?[$_[0]])/!
    }, 'pattern'],
);

my @names = (
    ['REGEX IMPLEMENTATION UNKNOWN NAME', q!\N{REGEX IMPLEMENTATION UNKNOWN NAME}!],
    ['', q!\N{}!],
);

for my $named (@names) {
    my ($name, $escape) = @$named;
    for my $context (@contexts) {
        my ($label, $wrap, $suffix) = @$context;
        my @warnings;
        {
            local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
            my $source = $wrap->($escape);
            eval "#line 1 unicode_named_unknown_diagnostics.t\n$source";
        }
        my ($first_line) = split /\n/, $@;
        is($first_line,
            "Unknown charname '$name' at unicode_named_unknown_diagnostics.t line 1, within $suffix",
            "$label unknown charname '$name' has the Perl diagnostic");
        is(scalar @warnings, 0,
            "$label unknown charname '$name' emits no warning before the fatal");
    }
}

done_testing;
