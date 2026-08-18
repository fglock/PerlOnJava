use strict;
use warnings;
use Test::More;

ok('ab' =~ /^(a)(?(1)b)$/,
    'numeric yes-only conditional consumes its required branch');
ok('a' !~ /^(a)(?(1)b)$/,
    'numeric yes-only conditional does not make the required branch optional');

ok('ab' =~ /^(a)?(?(1)b|c)$/,
    'numeric conditional takes the yes branch when its capture participated');
is($1, 'a', 'true numeric condition publishes its capture');
ok('c' =~ /^(a)?(?(1)b|c)$/,
    'numeric conditional takes the no branch when its capture did not participate');
ok(!defined($1), 'false numeric condition leaves its capture undefined');

ok('ac' =~ /^(a)?(?(1)b|ac)$/,
    'conditional capture state is reevaluated after backtracking');
ok(!defined($1), 'backtracking clears the abandoned conditional capture');

ok('xy' =~ /^(?<x>x)(?(<x>)y)$/,
    'named yes-only conditional takes its required branch');
ok('x' !~ /^(?<x>x)(?(<x>)y)$/,
    'named yes-only conditional does not make its branch optional');
ok('z' =~ /^(?<x>x)?(?(<x>)y|z)$/,
    'named conditional takes its no branch');

ok('a' =~ /^(?(?=a)a|b)$/,
    'positive assertion conditional takes its yes branch');
ok('b' =~ /^(?(?=a)a|b)$/,
    'positive assertion conditional takes its no branch');
ok('a' =~ /^(?(?!a)b|a)$/,
    'negative assertion conditional takes its no branch');
ok('b' =~ /^(?(?!a)b|a)$/,
    'negative assertion conditional takes its yes branch');

my $loader = qr/
    ^ (?<name> \w+)
    (?<open> [(])?
    (?<arg> [^)]*)
    (?(<open>) [)])
    $
/x;
ok('call(value)' =~ $loader, 'compiled named conditional matches');
is($+{arg}, 'value', 'compiled named conditional preserves later captures');

my $text = '${foo} $bar';
my $subst = qr/(^|\G|[^\\])\$(\{)?([A-Za-z][\w-]*)(?(2)\})/;
$text =~ s/$subst/$1 . uc($3)/eg;
is($text, 'FOO BAR', 'yes-only numeric conditional works in substitution');

ok('?((' =~ /^\?\(\($/,
    'escaped literal conditional introducer remains ordinary pattern text');

sub compile_error {
    my ($source) = @_;
    local $SIG{__WARN__} = sub {};
    eval "qr/$source/";
    return $@;
}

ok(length(compile_error('(?(1)x|y|z)')),
    'conditional with too many branches remains rejected');
ok(length(compile_error('(?(bogus)x|y)')),
    'unknown conditional remains rejected');
ok(length(compile_error('(?(1)x')),
    'unterminated conditional remains rejected');

done_testing;
