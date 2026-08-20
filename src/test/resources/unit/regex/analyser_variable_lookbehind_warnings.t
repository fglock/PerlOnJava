use strict;
use warnings;
use Test::More tests => 10;

for my $pattern (
    '(?<=(p|qq|rrr))',
    '(?<!(p|qq|rrr))',
    '(?| (?=(foo)) | (?<=(foo)|p) )',
    '(?| (?=(foo)) | (?<!(foo)|p) )',
) {
    my $warning = '';
    my $compiled;
    {
        local $SIG{__WARN__} = sub { $warning .= $_[0] };
        $compiled = eval "qr/$pattern/";
    }
    ok(defined $compiled, "$pattern compiles");
    like($warning, qr/Variable length .*lookbehind.*experimental/,
        "$pattern emits the experimental lookbehind warning");
}

my $suppressed = '';
{
    no warnings 'experimental::vlb';
    local $SIG{__WARN__} = sub { $suppressed .= $_[0] };
    my $compiled = eval q{ qr/(?<=(p|qq|rrr))/ };
    ok(defined $compiled, 'suppressed variable lookbehind compiles');
}
is($suppressed, '', 'experimental::vlb suppression controls the warning');
