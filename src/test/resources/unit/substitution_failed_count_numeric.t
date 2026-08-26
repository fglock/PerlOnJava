use strict;
use warnings FATAL => 'all';
use Test::More;

for my $modifier ('', 'g') {
    my $text = 'unrelated text';
    my $count = $modifier eq 'g'
        ? ($text =~ s/constraint//gi)
        : ($text =~ s/constraint//i);

    ok defined $count, "failed s///$modifier returns a defined scalar";
    ok !$count, "failed s///$modifier result is false";
    is "$count", '', "failed s///$modifier result stringifies to empty";
    is $count + 0, 0, "failed s///$modifier is numeric zero without a warning";
    is $count == 0, 1, "failed s///$modifier compares numerically as zero";
}

my $single = 'constraint constraint';
is $single =~ s/constraint//i, 1, 'successful single substitution returns its count';

my $global = 'constraint constraint';
is $global =~ s/constraint//gi, 2, 'successful global substitution returns its count';

done_testing;
