use strict;
use warnings;
use Test::More tests => 4;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{
        use 5.036;
        use warnings;
        sub helper($value) { $value }
        sub ordinary_call($value) { helper($value) }
        1;
    };
}
is($@, '', 'ordinary named calls in a signatured sub compile');
is(scalar @warnings, 0,
    'ordinary named calls do not imply use of @_');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{
        use 5.036;
        use warnings;
        sub implicit_args($value) { &helper }
        1;
    };
}
is($@, '', 'an explicit ampersand call with implicit arguments compiles');
like(join('', @warnings),
    qr/Implicit use of \@_ in subroutine entry with signatured subroutine is experimental/,
    'an explicit ampersand call still warns when it passes @_ implicitly');
