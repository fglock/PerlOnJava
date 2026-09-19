use strict;
use warnings;
use Test::More;

sub escaping_last { last }

my @warnings;
my $ok;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval { escaping_last(); 1 };
}

ok !defined $ok, 'last escapes the surrounding eval';
like join('', @warnings), qr/Exiting subroutine via last/, 'escaped last warns while leaving subroutine';

done_testing;
