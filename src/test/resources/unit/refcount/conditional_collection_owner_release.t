use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our $destroyed = 0;
{ package ConditionalCollectionOwner; sub DESTROY { ++$main::destroyed } }
sub array_result {
    if (my @items = (bless({value => 1}, 'ConditionalCollectionOwner'))) { @items }
}
sub hash_result {
    if (my %items = (one => bless({value => 2}, 'ConditionalCollectionOwner'))) { %items }
}
my @array = array_result();
is($array[0]{value}, 1, 'conditional array returns its elements');
is($destroyed, 0, 'returned array child remains alive');
my $weak = $array[0]; weaken($weak);
@array = ();
ok(!defined $weak, 'conditional array child releases after caller array');
my %hash = hash_result();
is($hash{one}{value}, 2, 'conditional hash returns its pairs');
is($destroyed, 1, 'returned hash child remains alive');
$weak = $hash{one}; weaken($weak);
%hash = ();
ok(!defined $weak, 'conditional hash child releases after caller hash');
done_testing();
