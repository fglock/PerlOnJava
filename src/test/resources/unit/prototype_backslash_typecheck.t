use strict;
use warnings;
use Test::More;

sub wants_hash_ref(\%) { scalar keys %{ $_[0] } }
sub wants_array_ref(\@) { scalar @{ $_[0] } }

my $hash_error = eval q{ wants_hash_ref my @not_hash; 1 };
ok(!$hash_error, 'hash prototype rejects private array argument');
like($@, qr/must be hash/i, 'hash prototype reports hash requirement');

my $array_error = eval q{ wants_array_ref my %not_array; 1 };
ok(!$array_error, 'array prototype rejects private hash argument');
like($@, qr/must be array/i, 'array prototype reports array requirement');

my %hash = (a => 1, b => 2);
my @array = (1, 2, 3);
is(wants_hash_ref(%hash), 2, 'hash prototype still accepts hash variables');
is(wants_array_ref(@array), 3, 'array prototype still accepts array variables');

done_testing();
