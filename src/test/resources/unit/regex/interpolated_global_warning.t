use strict;
use Test::More tests => 4;

my $fragment = '\m';

{
    local $^W = 1;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = qr/a$fragment/;
    is(scalar @warnings, 1, 'global warning switch diagnoses interpolated escape');
}

{
    local $^W = 1;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    no warnings 'regexp';
    my $compiled = qr/a$fragment/;
    is(scalar @warnings, 0, 'lexical suppression overrides global warning switch');
}

{
    local $^W;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = qr/a$fragment/;
    is(scalar @warnings, 0, 'interpolated escape warning is off by default');
}

{
    use re 'strict';
    local $^W;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $compiled = qr/a$fragment/;
    is(scalar @warnings, 1, 'strict regex mode enables interpolated escape warning');
}
