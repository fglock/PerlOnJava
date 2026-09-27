use strict;
use warnings;
use Test::More;

sub ascending($$) { $_[0] <=> $_[1] }

my $comparator_glob = \*ascending;
is_deeply([sort $comparator_glob 3, 1, 2], [1, 2, 3],
    'a typeglob comparator uses its CODE slot');

my $dynamic_comparator = 'ascending';
is_deeply([sort $dynamic_comparator 3, 1, 2], [1, 2, 3],
    'a scalar comparator name is resolved at sort entry');

{
    package Local::SortValues;
    sub values { 3, 1, 2 }
}

my $values = bless {}, 'Local::SortValues';
is_deeply([sort $values->values], [1, 2, 3],
    'a scalar method call is a sort list expression, not a comparator');

my $error = eval '() = sort; 1';
ok(!defined($error), 'an empty sort is rejected');
like($@, qr/^Not enough arguments for sort/, 'empty sort has Perl diagnostic');

{
    package Local::SortCounter;

    our $count = 0;
    sub new { ++$count; bless [], shift }
    sub DESTROY { --$count }
    sub comparator($$) {
        my ($left, $right) = @_;
        my $keep_args_alive = \@_;
        return $left <=> $right;
    }
}

my @values = map { Local::SortCounter->new } 0 .. 1;
my @sorted = sort Local::SortCounter::comparator @values;
@values = ();
@sorted = ();
is($Local::SortCounter::count, 0,
    'a $$ sort comparator does not retain its temporary argument frame');

done_testing();
