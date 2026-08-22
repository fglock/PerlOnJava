use strict;
use warnings;
use re 'eval';
use Test::More tests => 2;

our (@ctl_n, @plus);
our $f = sub { defined $_[0] ? $_[0] : 'undef' };
my @warnings;
my $matched;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $re = qr/(1)((??{ push @ctl_n, $f->($^N); push @plus, $f->($+); $^N + 1})){2}(?{$^N})(|a(b)c|def)(??{"$^R"})/;
    $matched = "123abc3" =~ /^(??{$re})$/;
}

ok($matched, 'quantified postponed pattern preserves the successful match');
is_deeply(\@warnings, [], 'unknown-width dynamic callout emits no zero-length warning');
