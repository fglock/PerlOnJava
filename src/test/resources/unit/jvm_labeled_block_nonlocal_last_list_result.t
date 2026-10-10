use strict;
use warnings;
use Test::More;

sub helper { no warnings qw{exiting}; last SKIP }

sub tail_labeled_block { SKIP: { helper(); } }
sub non_tail_labeled_block { SKIP: { helper(); 1 } }

my @tail = tail_labeled_block();
is scalar(@tail), 0, 'tail labeled block does not return the unmatched last marker';

my @non_tail = non_tail_labeled_block();
is scalar(@non_tail), 0, 'non-tail labeled block does not return the unmatched last marker';

my $scalar = tail_labeled_block();
ok !defined($scalar), 'scalar context remains undef after last exits the labeled block';

done_testing;
