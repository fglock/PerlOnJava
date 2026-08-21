use strict;
use Test::More;

sub evaluate {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    local $^W;
    my $result = eval $source;
    return ($result, $@, \@warnings);
}

sub wildcard_warnings {
    return [grep {
        /Unicode property wildcards feature/
            || index($_, 'single character results returned') >= 0
    } @{$_[0]}];
}

my $pattern = q{$_ = 'x'; m!(?[\p{name=/KATAKANA/}])$!};
my $category = 'experimental::uniprop_wildcards';

my (undef, $default_error, $default) = evaluate($pattern);
is $default_error, '', 'non-strict wildcard pattern compiles by default';
is scalar(@$default), 1, 'experimental wildcard warning is default-on';
like $default->[0], qr/^The Unicode property wildcards feature is experimental/,
    'default warning retains its experimental text';

my (undef, $enabled_error, $enabled) = evaluate(
    q{use warnings; } . $pattern);
is $enabled_error, '', 'wildcard pattern compiles with warnings enabled';
is scalar(@$enabled), 2, 'enabling warnings retains both wildcard diagnostics';
like $enabled->[1], qr/^Using just the single character results returned by \\p\{\}/,
    'enabled warning retains the positioned single-character diagnostic';

my (undef, $suppressed_error, $suppressed) = evaluate(
    qq{no warnings '$category'; } . $pattern);
is $suppressed_error, '', 'suppressed wildcard pattern compiles';
is scalar(@$suppressed), 0, 'explicit category suppression wins';

my (undef, $strict_error, $strict) = evaluate(
    q{no warnings 'experimental::re_strict'; use re 'strict'; } . $pattern);
is $strict_error, '', 'strict wildcard pattern compiles';
my $strict_wildcards = wildcard_warnings($strict);
ok scalar(@$strict_wildcards) >= 1, 'strict mode retains a wildcard warning';
like join('', @$strict_wildcards), qr/The Unicode property wildcards feature is experimental/,
    'strict mode retains the experimental warning text';

my (undef, $strict_suppressed_error, $strict_suppressed) = evaluate(
    q{no warnings 'experimental::re_strict'; use re 'strict'; }
    . qq{no warnings '$category'; } . $pattern);
is $strict_suppressed_error, '', 'strict suppressed wildcard pattern compiles';
unlike join('', @$strict_suppressed),
    qr/The Unicode property wildcards feature is experimental/,
    'explicit experimental warning suppression wins under strict mode';

my (undef, $fatal_error, $fatal) = evaluate(
    qq{use warnings FATAL => '$category'; } . $pattern);
like $fatal_error, qr/^The Unicode property wildcards feature is experimental/,
    'fatal category turns the default warning into an exception';
is scalar(@$fatal), 0, 'fatal warning is not also delivered to the warning hook';

done_testing;
