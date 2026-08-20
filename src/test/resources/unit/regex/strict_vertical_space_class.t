use strict;
use warnings;
use Test::More tests => 3;

my $vertical = "\f";
my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval "no warnings 'experimental::re_strict'; use re 'strict'; qr/[a${vertical}b]/";
}
like($@,
    qr/^Literal vertical space in \[\] is illegal except under \/x in regex; marked by <-- HERE in m\/\[a\f <-- HERE b\]\//,
    'strict class rejects literal vertical space at its exact position');
is_deeply(\@warnings, [], 'strict rejection emits no secondary warnings');

@warnings = ();
{
    local $SIG{__WARN__} = sub { };
    eval "no warnings 'experimental::re_strict'; use re 'strict'; qr/[a${vertical}b]/xx";
}
is($@, '', 'strict /xx class accepts literal vertical space');
