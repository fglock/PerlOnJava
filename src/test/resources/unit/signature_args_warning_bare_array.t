use v5.36;
use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval q{
        use v5.36;
        use warnings 'experimental::args_array_with_signatures';
        sub use_args ($value) { @_ }
        1;
    };
    ok($ok, 'bare @_ in a signatured subroutine compiles');
}

is(scalar(grep { /Use of \@_ in line sequence with signatured subroutine is experimental/ } @warnings),
    1, 'bare @_ emits the expected experimental warning');
