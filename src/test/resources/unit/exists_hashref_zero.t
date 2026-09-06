use strict;
use warnings;
use Test::More;

my $options = { default => 0 };
ok exists $options->{default}, 'a zero-valued hashref entry exists';
done_testing;
