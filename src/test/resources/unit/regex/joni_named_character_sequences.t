use strict;
use warnings;
use Test::More tests => 23;
use lib 'src/test/resources/unit/lib';
use RegexImplementationCname;

ok('foo' =~ /^\N{foo}$/, 'multi-character expansion matches as one atom');
ok('foofoo' =~ /^(?:\N{foo}){2}$/, 'multi-character expansion can be repeated');
ok('xfoofoo' =~ /^x\N{foo}{2}$/, 'sequence quantifier does not bind the prior atom');
ok('ab' =~ /^a\N{EMPTY-STR}b$/, 'empty expansion is zero-width');
ok('' =~ /^\N{EMPTY-STR}$/, 'empty expansion forms an empty regex');
ok('b' !~ /^a\N{EMPTY-STR}?b$/, 'empty quantifier does not bind the prior atom');
ok('WARN' =~ /^[\N{WARN}]$/, 'multi-character class expansion is an atomic alternative');
ok('W' !~ /^[\N{WARN}]$/, 'class sequence does not expose its first member');
ok('N' !~ /^[\N{WARN}]$/, 'class sequence does not expose its final member');

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    my $regex = eval q{qr/[\N{EMPTY-STR}q]/};
    ok('q' =~ $regex, 'empty class expansion is ignored');
}
like($warning, qr/Ignoring zero length \\N\{\} in character class/,
    'empty class expansion warns');

my $negated_warning = '';
my $negated;
{
    local $SIG{__WARN__} = sub { $negated_warning .= join '', @_ };
    $negated = eval q{qr/^[^\N{WARN}]$/};
}
ok('A' =~ $negated, 'negated sequence class accepts another character');
ok('N' !~ $negated, 'negated sequence class excludes Perl\'s first sequence member');
like($negated_warning, qr/Using just the first character returned by \\N\{\}/,
    'negated sequence class warns about first-character semantics');

$RegexImplementationCname::Evil = 'A';
my $cached = eval q{qr/^(\N{EVIL})$/};
is($RegexImplementationCname::Evil, 'AB', 'translator runs once when regex is compiled');
ok('A' =~ $cached, 'cached regex matches first time');
ok('A' =~ $cached, 'cached regex matches again');
is($RegexImplementationCname::Evil, 'AB', 'matching does not rerun translator');

my $next = eval q{qr/^(\N{EVIL})$/};
ok('AB' =~ $next, 'same spelling keeps the next lexical expansion');
ok('A' !~ $next, 'semantic cache key does not reuse the prior expansion');
is($RegexImplementationCname::Evil, 'ABC', 'each distinct regex compile translates once');
ok('xy' =~ 'x\N{EMPTY-STR}y', 'runtime string pattern inherits lexical translator');
is("\N{foo}", 'foo', 'string expansion shares translator semantics');
