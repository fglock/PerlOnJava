use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    use warnings 'imprecision';

    # This is a computed UV-sized integer, not a retained integer literal.
    # Perl warns twice as repeated increments cross an NV precision boundary.
    my $uv_max = 1 + 2 * (~0 >> 1);
    my $value = $uv_max - 1;
    ++$value for 0 .. 3;
}

is(scalar @warnings, 2,
   'computed large integer increments emit two imprecision warnings');
like($warnings[0], qr/Lost precision when incrementing \d+/,
     'first computed-integer warning identifies increment precision loss');
like($warnings[1], qr/Lost precision when incrementing \d+/,
     'second computed-integer warning identifies increment precision loss');

done_testing;
