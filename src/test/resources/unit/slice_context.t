use strict;
use warnings;
use Test::More tests => 4;

my %seen;
my @empty_assignment_keys = ('title');
my $empty_assignment_count = (@seen{@empty_assignment_keys} = ());
is($empty_assignment_count, 0,
    'hash slice assignment with empty RHS returns zero in scalar context');
is_deeply([sort keys %seen], ['title'],
    'hash slice assignment still evaluates array keys in list context');

sub keys_for_slice {
    return wantarray ? qw(a b) : 'scalar';
}

my %assigned;
@assigned{keys_for_slice()} = (1, 2);
is_deeply([sort keys %assigned], [qw(a b)],
    'hash slice assignment calls key expressions in list context');

my %source = (a => 10, b => 20, scalar => 99);
is_deeply([@source{keys_for_slice()}], [10, 20],
    'hash slice read calls key expressions in list context');
