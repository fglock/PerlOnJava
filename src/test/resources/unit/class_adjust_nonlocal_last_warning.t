use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

# An ADJUST block runs from the generated constructor, so the statements inside
# it have no lexical warning bits installed at runtime.  A `last` escaping that
# block must still follow the warnings in effect where it was written.
class ADJ_Warns { field $x; ADJUST { last; } }
class ADJ_Quiet { field $x; ADJUST { no warnings 'exiting'; last; } }

my @warnings;
my $ok;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval { ADJ_Warns->new for 0; 1 };
}
ok !$ok, 'last in ADJUST does not escape the eval';
like $warnings[0], qr/\AExiting subroutine via last at \S+ line \d+/,
    'ADJUST last warns when its scope enables exiting';

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval { ADJ_Quiet->new for 0; 1 };
}
is_deeply [grep { /Exiting subroutine/ } @warnings], [],
    'ADJUST with no warnings exiting stays quiet';

done_testing;
