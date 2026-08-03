use strict;
use warnings;

use lib 'perl5/cpan/Test-Deep/lib';
use Scalar::Util qw(weaken);
use Test::Deep qw(eq_deeply);
use Test::More;

sub left {
    my ($ref) = @_;
    eq_deeply($ref, []);
    return 'left';
}

sub right {
    my ($ref) = @_;
    eq_deeply([], $ref);
    return 'right';
}

for my $sub (\&left, \&right) {
    my $ref = [];
    my $weak = $ref;
    weaken($weak);
    my $side = $sub->($ref);
    $ref = 1;
    ok(!defined($weak), "$side does not capture the compared reference");
}

done_testing;
