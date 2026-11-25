use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use warnings 'closure';
use Test::More;

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval <<'PERL';
no warnings 'void';
sub {
    my sub x {};
    sub not_lexical8 { \&x }
}->()();
PERL
}
is(scalar(() = $warning =~ /Subroutine "&x" is not available at /g), 1,
    'the anonymous-scope unavailable warning is emitted once');
like($warning, qr/Subroutine "&x" is not available at /,
    'a lexical sub in an anonymous sub keeps its unavailable warning');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    my $result = eval q{
        sub stayshared { my sub x; sub notstayshared { x } }
        1;
    };
    is($@, '', 'named subs with a forward lexical sub compile');
}
like($warning, qr/Subroutine "&x" will not stay shared at /,
    'a forward lexical sub inside a named sub keeps its sharing warning');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval q{
        my sub file_scope_helper {};
        sub calls_file_scope_helper { file_scope_helper() }
        1;
    };
    is($@, '', 'a file-scope lexical sub can be called by a named sub');
}
is($warning, '', 'a file-scope lexical sub does not trigger a closure warning');

done_testing();
