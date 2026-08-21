use strict;
use warnings;
use Test::More tests => 5;

my @warnings;
my $compiled;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $compiled = eval q{
        no warnings 'experimental::re_strict';
        use re 'strict';
        qr/[[   ^   :   x d i g i t   :   ]   ]\x{100}/;
    };
}

ok(defined $compiled, 'spaced POSIX candidate compiles under re strict');
is(scalar @warnings, 6, 'spaced POSIX candidate emits six warnings');
is(scalar(grep { /no blanks are allowed in one/ } @warnings), 4,
   'four spacing diagnostics are retained');
is(scalar(grep { /the '\^' must come after the colon/ } @warnings), 1,
   'the misplaced-caret diagnostic is retained');
is(scalar(grep { /Unescaped literal '\]'/ } @warnings), 1,
   'the strict lexer emits the closing-bracket warning once');
