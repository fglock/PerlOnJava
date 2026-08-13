#!/usr/bin/env perl
use strict;
use warnings;
use threads;
use threads::shared;

my @documents = (
    'perl threads isolate ordinary values',
    'java threads coordinate shared work',
    'perl workers return local results',
    'shared schedulers keep bulk state local',
    'java hosts independent perl runtimes',
    'threads make ownership explicit',
);
my @original = @documents;
my $next_document :shared = 0;

sub map_documents {
    my %counts;
    my @processed;
    while (1) {
        my $index;
        {
            lock($next_document);
            $index = $next_document++;
        }
        last if $index >= @documents;
        push @processed, $index;
        ++$counts{$_} for $documents[$index] =~ /[a-z]+/g;
    }
    return { counts => \%counts, processed => \@processed };
}

my @workers = map { threads->create(\&map_documents) } 1 .. 3;
my (%total, @processed);
for my $worker (@workers) {
    my $partial = $worker->join;
    $total{$_} += $partial->{counts}{$_} for keys %{$partial->{counts}};
    push @processed, @{$partial->{processed}};
}

die "a document was lost or processed twice"
    unless join(',', sort { $a <=> $b } @processed) eq '0,1,2,3,4,5';
die "parent input was mutated" unless join("\n", @documents) eq join("\n", @original);
die "unexpected word counts"
    unless $total{perl} == 3 && $total{threads} == 3
        && $total{java} == 2 && $total{shared} == 2;

print "processed 6 documents with 3 workers; "
    . "perl=$total{perl}, threads=$total{threads}, java=$total{java}, shared=$total{shared}\n";
