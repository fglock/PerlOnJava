use strict;
use warnings;
use Test::More tests => 7;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/\b{g}/};
}
is($@, '', 'short grapheme-boundary alias compiles');
is_deeply(\@warnings, [], 'short grapheme-boundary alias is silent in Unicode mode');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/\b{g}/a};
}
is($@, '', 'short grapheme-boundary alias compiles under /a');
is(scalar @warnings, 1, 'short grapheme-boundary alias warns once under /a');
like($warnings[0], qr/^Using \/u for '\\b\{g\}' instead of \/a in regex;/,
    'short alias warning preserves its source spelling');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{qr/\B{gcb}/a};
}
is($@, '', 'long grapheme-boundary spelling compiles under /a');
like($warnings[0], qr/^Using \/u for '\\B\{gcb\}' instead of \/a in regex;/,
    'long spelling warning preserves negation and source spelling');
