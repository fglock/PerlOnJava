use strict;
use warnings;
use Test::More tests => 7;

my $data_uri = "data:foo;base64," . ("Pj4+" x 2000);
ok(
    $data_uri =~ /^(?:[a-zA-Z][a-zA-Z0-9.+\-]*:)?([^\#]*)/,
    'long URI-style opaque regex matches without stack overflow',
);
is($1, substr($data_uri, 5), 'long opaque capture is preserved');

my $with_fragment = "data:" . ("x" x 5000) . "#frag";
ok(
    $with_fragment =~ /^([a-zA-Z][a-zA-Z0-9.+\-]*:)?([^\#]*)(\#.*)?$/,
    'long opaque regex with optional fragment matches',
);
is($2, "x" x 5000, 'opaque capture before fragment is preserved');
is($3, "#frag", 'fragment capture is preserved');

my $patn = q(\Qabc\E);
my $re;
{
    no warnings 'regexp';
    $re = qr/[$patn]/;
}

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    "abc" =~ /($re)/;
}
like(
    join('', @warnings),
    qr/Unrecognized escape \\Q in character class passed through in regex.*Unrecognized escape \\E in character class passed through in regex/s,
    'interpolated bad character-class escapes warn when the regex is used',
);

@warnings = ();
{
    no warnings 'regexp';
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    "abc" =~ /($re)/;
}
is(join('', @warnings), '', 'no warnings regexp suppresses use-time bad escape warnings');
