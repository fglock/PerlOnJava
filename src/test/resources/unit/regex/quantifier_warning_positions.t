use strict;
use warnings;
use Test::More tests => 8;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/x{3,1}/};
}
is($@, '', 'descending interval remains a nonfatal pattern');
is(scalar @warnings, 1, 'descending interval warns once');
like($warnings[0],
    qr/^Quantifier \{n,m\} with n > m can't match in regex; marked by <-- HERE in m\/x\{3,1\} <-- HERE \//,
    'descending interval warning points after the quantifier');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/a{1,1}?/};
}
is($@, '', 'fixed non-greedy interval remains a valid pattern');
is(scalar @warnings, 1, 'fixed non-greedy interval warns once');
like($warnings[0],
    qr/^Useless use of greediness modifier '\?' in regex; marked by <-- HERE in m\/a\{1,1\}\? <-- HERE \//,
    'fixed non-greedy warning points after the modifier');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/a{1,2}?/};
}
is($@, '', 'variable non-greedy interval remains valid');
is_deeply(\@warnings, [], 'variable non-greedy interval remains useful and silent');
