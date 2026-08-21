use strict;
use Test::More;

sub captured_eval {
    my ($source) = @_;
    my @warnings;
    local $^W;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $result = eval $source;
    return ($result, $@, join('', @warnings));
}

my $pattern = q{/(?c)\x{100}/};
my $strict = q{no warnings 'experimental::re_strict'; use re 'strict'; };

my (undef, $suppressed_error, $suppressed_warning) =
    captured_eval("$strict no warnings 'regexp'; $pattern");
is($suppressed_error, '',
    'strict regex compiles when its ordinary warning is disabled');
is($suppressed_warning, '',
    'an explicit no warnings regexp scope suppresses the strict warning');

my (undef, $default_error, $default_warning) =
    captured_eval("$strict $pattern");
is($default_error, '',
    'the same strict regex compiles after the suppressed cache warmup');
like($default_warning, qr/Useless \(\?c\)/,
    're strict enables its warning by default after cache reuse');

my (undef, $suppressed_again_error, $suppressed_again_warning) =
    captured_eval("$strict no warnings 'regexp'; $pattern");
is($suppressed_again_error, '',
    'the cached strict regex remains usable in a later suppressed scope');
is($suppressed_again_warning, '',
    'cached default-on warnings still honor explicit suppression');

done_testing;
