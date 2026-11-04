use strict;
use warnings;
use Test::More;

my $source_loaded = eval q{
    package Local::StashEachCopy::Source;
    our @HitSortEnum = qw(NoSort RandomSort LessByDate);
    our %HitSortEnum = (NoSort => 0, RandomSort => 15, LessByDate => 1);

    foreach my $ename (@HitSortEnum) {
        no strict 'refs';
        *$ename = sub { return $HitSortEnum{$ename}; };
    }
    1;
};
die $@ unless $source_loaded;

{
    no strict 'refs';
    my $source = \%{'Local::StashEachCopy::Source::'};
    my $destination = \%{'Local::StashEachCopy::Destination::'};
    while (my ($name, $glob) = each %$source) {
        $destination->{$name} = $glob;
    }
}

my %expected = (NoSort => 0, RandomSort => 15, LessByDate => 1);
for my $name (qw(NoSort RandomSort LessByDate)) {
    is(
        Local::StashEachCopy::Source->can($name)->(),
        $expected{$name},
        "$name source closure retains its captured enum value",
    );
    is(
        Local::StashEachCopy::Destination->can($name)->(),
        $expected{$name},
        "$name remains callable after copying stash entries returned by each",
    );
    is(
        Local::StashEachCopy::Destination->can($name),
        Local::StashEachCopy::Source->can($name),
        "$name keeps its coderef identity after copying stash entries returned by each",
    );
}

done_testing;
