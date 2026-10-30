use strict;
use warnings;
use feature qw(state refaliasing declared_refs);
no warnings qw(experimental::refaliasing experimental::declared_refs);
use Scalar::Util qw(weaken);
use Test::More;
{
    my @source = ({ value => 21 });
    my $callback = do {
        my $reference = \@source;
        sub { \state @items = $reference; return $items[0] }
    };
    my $weak = $callback->(); weaken($weak);
    undef $callback;
    ok(defined $weak, 'source array binding survives state alias release');
    is($source[0]{value}, 21, 'source array contents remain usable');
    @source = ();
    ok(!defined $weak, 'source array clear releases final element owner');
}
{
    my %source = (item => { value => 22 });
    my $callback = do {
        my $reference = \%source;
        sub { \state %items = $reference; return $items{item} }
    };
    my $weak = $callback->(); weaken($weak);
    undef $callback;
    ok(defined $weak, 'source hash binding survives state alias release');
    is($source{item}{value}, 22, 'source hash contents remain usable');
    %source = ();
    ok(!defined $weak, 'source hash clear releases final element owner');
}
done_testing();
