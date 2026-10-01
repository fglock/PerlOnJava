use strict;
use warnings;
use Test::More;

no warnings 'exiting';
{
    package TiedConstructorControlFlow;
    sub TIESCALAR {
        my $value;
        next;
        return bless \$value, shift;
    }
}

my $value;
my $ok = eval {
    tie $value, 'TiedConstructorControlFlow';
    1;
};
ok(!$ok, 'loop control cannot escape a TIESCALAR constructor');
like $@, qr/^Can't "next" outside a loop block/,
    'escaped loop control reports the Perl diagnostic';

done_testing();
