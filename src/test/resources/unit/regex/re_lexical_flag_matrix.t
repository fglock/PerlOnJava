use strict;
use warnings;
use Test::More;
use re qw(regexp_pattern);

sub modifiers {
    my ($regex) = @_;
    return (regexp_pattern($regex))[1];
}

{
    use re '/d';
    is(modifiers(qr/foo/), '', q{/d is the non-stringified default charset});
}
{
    use locale;
    use re '/d';
    is(modifiers(qr/foo/), '', q{/d overrides an ambient locale pragma});
}
{
    use re '/l';
    is(modifiers(qr/foo/), 'l', q{/l supplies locale charset semantics});
}
{
    use re '/u';
    is(modifiers(qr/foo/), 'u', q{/u supplies Unicode charset semantics});
    ok("\x{100}" =~ /^\w$/, q{/u makes word classes Unicode-aware});
}
{
    use re '/a';
    is(modifiers(qr/foo/), 'a', q{/a supplies ASCII charset semantics});
    ok("\x{100}" !~ /^\w$/, q{/a restricts word classes to ASCII});
}
{
    use charnames ':full';
    use re '/aa';
    is(modifiers(qr/foo/), 'aa', q{/aa stringifies as two ASCII flags});
    ok("\N{KELVIN SIGN}" !~ /^k$/i,
       q{/aa blocks ASCII/non-ASCII case-fold crossings});
}

{
    use re '/ims';
    ok("A\nB" =~ /^a.b$/i, q{/i, /m, and /s are lexical defaults});
    is(modifiers(qr/foo/), 'msi', q{ordinary lexical flags use Perl order});
}
{
    use re '/x';
    ok('ab' =~ /a b/, q{/x ignores pattern whitespace});
}
{
    use re '/xx';
    ok(' ' !~ /[ a]/, q{/xx also ignores class whitespace});
    is(modifiers(qr/foo/), 'xx', q{/xx retains both extended levels});
}
{
    use re '/n';
    'ab' =~ /(a)(?<named>b)/;
    is($1, 'b', q{/n suppresses unnamed captures but retains named captures});
    is($+{named}, 'b', q{/n publishes the named capture});
}
{
    use re '/p';
    'abc' =~ /b/;
    is(${^MATCH}, 'b', q{/p preserves the complete match});
    is(${^PREMATCH}, 'a', q{/p preserves the prematch});
    is(${^POSTMATCH}, 'c', q{/p preserves the postmatch});
    like("" . qr/foo/, qr/^\(\?\^[^:]*p[^:]*:foo\)$/,
         q{/p is retained by qr stringification});
}

{
    use re '/aaimsnpxx';
    is(modifiers(qr/foo/), 'aamsixxnp',
       q{charset and ordinary lexical defaults compose});
}

{
    use re '/uim';
    is(modifiers(qr/foo/), 'umi', 'outer lexical defaults are active');
    {
        no re '/ui';
        is(modifiers(qr/foo/), 'm', 'nested no re selectively cancels defaults');
        ok('K' !~ /k/, 'cancelled /i changes matching behavior');
    }
    is(modifiers(qr/foo/), 'umi', 'block exit restores outer lexical defaults');
    ok('K' =~ /k/, 'restored /i changes matching behavior back');
}

{
    use re '/u';
    use re '/a';
    is(modifiers(qr/foo/), 'a', 'a later charset pragma replaces /u');
    {
        no re '/a';
        is(modifiers(qr/foo/), '', 'no re cancels the matching charset mode');
    }
    is(modifiers(qr/foo/), 'a', 'charset mode restores at block exit');
}

{
    use re '/a';
    is(modifiers(qr/foo/u), 'u',
       'an explicit /u overrides the lexical /a charset');
}
{
    use re '/u';
    is(modifiers(qr/foo/a), 'a',
       'an explicit /a overrides the lexical /u charset');
    is(modifiers(qr/foo/d), '',
       'an explicit /d overrides the lexical /u charset');
}
{
    use re '/aa';
    is(modifiers(qr/foo/l), 'l',
       'an explicit /l overrides the lexical /aa charset');
}

{
    use re '/aa';
    {
        no re '/a';
        is(modifiers(qr/foo/), 'aa', q{no re /a does not cancel /aa});
    }
    {
        no re '/aa';
        is(modifiers(qr/foo/), '', q{no re /aa cancels /aa});
    }
}

{
    use re '/xx';
    {
        no re '/x';
        is(modifiers(qr/foo/), 'x', q{no re /x removes one /xx level});
    }
    {
        no re '/xx';
        is(modifiers(qr/foo/), '', q{no re /xx removes both levels});
    }
}

sub pragma_warning {
    my ($source) = @_;
    my $warning = '';
    {
        local $SIG{__WARN__} = sub { $warning .= $_[0] };
        my $ok = eval "$source; 1";
        is($@, '', "$source remains nonfatal");
        ok($ok, "$source finishes compilation");
    }
    return $warning;
}

like(pragma_warning(q{use re '/iz'}),
     qr/Unknown regular expression flag "z"/,
     'unknown flag has Perl diagnostic');
like(pragma_warning(q{use re '/au'}),
     qr/The "a" and "u" flags are exclusive/,
     'exclusive charset flags have Perl diagnostic');
like(pragma_warning(q{use re '/uu'}),
     qr/The "u" flag may not appear twice/,
     'duplicate charset flag has Perl diagnostic');
like(pragma_warning(q{use re '/aaa'}),
     qr/The "a" flag may only appear a maximum of twice/,
     'excess /a has Perl diagnostic');
like(pragma_warning(q{use re '/xxx'}),
     qr/The "x" flag may only appear a maximum of twice/,
     'excess /x has Perl diagnostic');

sub pragma_result {
    my ($source) = @_;
    my $warning = '';
    my $regex;
    {
        local $SIG{__WARN__} = sub { $warning .= $_[0] };
        $regex = eval "$source; qr/foo/";
        is($@, '', "$source produces a regex after warning");
    }
    return ($warning, $regex);
}

{
    my ($warning, $regex) = pragma_result(q{use re '/iz'});
    like($warning, qr/Unknown regular expression flag "z"/,
         'mixed unknown option warns');
    is(modifiers($regex), '',
       'unknown flag discards valid characters from the same option');
}
{
    my ($warning, $regex) = pragma_result(q{use re '/au'});
    like($warning, qr/The "a" and "u" flags are exclusive/,
         'mixed charset option warns');
    is(modifiers($regex), 'u', 'last exclusive charset wins');
}
{
    my ($warning, $regex) = pragma_result(q{use re '/aaa'});
    like($warning, qr/maximum of twice/, 'three ASCII flags warn');
    is(modifiers($regex), 'a', 'three ASCII flags resolve to /a');
}
{
    my ($warning, $regex) = pragma_result(q{use re '/xxx'});
    like($warning, qr/maximum of twice/, 'three extended flags warn');
    is(modifiers($regex), 'xx', 'three extended flags resolve to /xx');
}
{
    my ($warning, $regex) = pragma_result(
        q{use re '/im'; no re '/iz'});
    like($warning, qr/Unknown regular expression flag "z"/,
         'unknown no re option warns');
    is(modifiers($regex), 'mi',
       'unknown no re option leaves all prior defaults unchanged');
}

done_testing;
