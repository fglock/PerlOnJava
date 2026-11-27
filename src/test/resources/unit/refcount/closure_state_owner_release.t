use strict;
use warnings;
use feature 'state';
use Scalar::Util qw(weaken);
use Test::More;

sub scalar_callback {
    my ($seed) = @_;
    return sub { state $metadata = { value => $seed }; return $metadata };
}
my $callback = scalar_callback(1);
my $weak = $callback->(); weaken($weak);
my $keeper = $callback;
undef $callback;
ok(defined $weak, 'another CODE owner preserves scalar state');
is($keeper->()->{value}, 1, 'remaining CODE owner can use scalar state');
undef $keeper;
ok(!defined $weak, 'final CODE owner releases scalar state');

sub array_callback {
    my ($seed) = @_;
    return sub { state @items = ({ value => $seed }); return \@items };
}
$callback = array_callback(2);
$weak = $callback->()->[0]; weaken($weak);
undef $callback;
ok(!defined $weak, 'final CODE owner releases state array contents');

sub hash_callback {
    my ($seed) = @_;
    return sub { state %items = (item => { value => $seed }); return \%items };
}
$callback = hash_callback(3);
my $escaped = $callback->();
$weak = $escaped->{item}; weaken($weak);
undef $callback;
ok(defined $weak, 'escaped state hash retains its contents after CODE release');
is($escaped->{item}{value}, 3, 'escaped state hash remains usable');
undef $escaped;
ok(!defined $weak, 'last escaped hash owner releases its contents');

sub scalar_cell_callback {
    my ($seed) = @_;
    return sub { state $item = { value => $seed }; return \$item };
}
$callback = scalar_cell_callback(4);
$escaped = $callback->();
$weak = $$escaped; weaken($weak);
undef $callback;
ok(defined $weak, 'escaped state scalar cell retains its contents');
is($$escaped->{value}, 4, 'escaped state scalar cell remains usable');
undef $escaped;
ok(!defined $weak, 'last scalar cell owner releases its contents');
done_testing();
