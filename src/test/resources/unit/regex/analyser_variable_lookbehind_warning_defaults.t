use strict;
use Test::More;

sub warnings_for {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    local $^W;
    my $compiled = eval $source;
    is $@, '', "compiled: $source";
    return \@warnings;
}

my $strict = q{no warnings 'experimental::re_strict'; use re 'strict'; };
my $pattern = q{m/(?<=(p|qq|rrr))/};

my $default = warnings_for($pattern);
like join('', @$default), qr/Variable length .*lookbehind.*experimental/,
    'experimental::vlb warning is default-on without use re strict';

my $default_suppressed = warnings_for(
    q{no warnings 'experimental::vlb'; } . $pattern);
is scalar(@$default_suppressed), 0,
    'non-strict explicit experimental::vlb suppression wins';

my $isolated = warnings_for($strict . $pattern);
like join('', @$isolated), qr/Variable length .*lookbehind.*experimental/,
    'use re strict makes experimental::vlb warning default-on';

my $suppressed = warnings_for(
    $strict . q{no warnings 'experimental::vlb'; } . $pattern);
is scalar(@$suppressed), 0, 'explicit experimental::vlb suppression wins';

my $after_suppression = warnings_for($strict . $pattern);
like join('', @$after_suppression), qr/Variable length .*lookbehind.*experimental/,
    'suppression in a prior eval does not leak into the next eval';

done_testing;
