use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 6;

sub compile_with_warnings {
    my ($source) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval $source;
    return ($@, @warnings);
}

my ($error, @warnings) = compile_with_warnings(q{qr/(?[[ : - \x3A ] ])/});
is($error, '', 'equivalent colon range compiles');
is(scalar @warnings, 1, 'equivalent colon range warns once');
like($warnings[0], qr/^": - \\x3A " is more clearly written simply as ":"/,
    'colon warning preserves both endpoint spellings');

($error, @warnings) = compile_with_warnings(q{qr/(?[[ \t - \x09 ] ])/});
is($error, '', 'equivalent tab range compiles');
like($warnings[0], qr/^"\\t - \\x09 " is more clearly written simply as "\\t"/,
    'tab warning uses the canonical escape');

($error, @warnings) = compile_with_warnings(q{qr/(?[[ % - % ] ])/});
is_deeply(\@warnings, [], 'identically spelled endpoints do not warn');
