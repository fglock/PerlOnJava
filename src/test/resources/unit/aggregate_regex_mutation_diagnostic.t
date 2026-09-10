use strict;
use warnings;
use Test::More;

my $substitution = eval q{my @items; @items =~ s/a/b/};
like($@, qr/Can't modify private array in substitution/, 'lexical array substitution is rejected');

my $match = eval q{my @items; @items =~ m/a/};
is($@, '', 'non-mutating aggregate match remains valid');

done_testing;
