use v5.40;
use Test::More tests => 3;

our $x = 1;
{
    my $x = 2;
    package DB;
    sub eval_caller_lexical { eval '$x' }
    sub eval_caller_lexical_with_own { my $x = 4; eval '$x' }
}

{
    my $x = 3;
    is(DB::eval_caller_lexical(), 3,
       'eval in package DB uses the active caller lexical');
    is(DB::eval_caller_lexical_with_own(), 3,
       'DB eval ignores the DB subroutine lexical');
}

is(eval { package Other; eval '$x' }, 1,
   'the caller-lexical rule is specific to package DB');
