use strict;
use warnings;
use Test::More;

our @warnings;
BEGIN { $SIG{__WARN__} = sub { push @main::warnings, @_ } }

{
    package SubsPragmaCustom;
    use strict;
    use warnings;

    use subs qw(foo);

    BEGIN {
        no strict 'refs';
        *{"SubsPragmaCustom::foo"} = sub { "generated" }
            unless *{"SubsPragmaCustom::foo"}{CODE};
    }

    sub foo { "custom" }
}

is(SubsPragmaCustom::foo(), "custom", "predeclared sub can be custom-defined later");
{
    no strict 'refs';
    ok(*{"SubsPragmaCustom::foo"}{CODE}, "predeclared sub has a visible CODE slot");
}
is_deeply(\@warnings, [], "predeclared custom sub does not warn as redefined");

done_testing;
