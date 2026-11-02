use strict;
use warnings;
use Test::More;
use PerlIO::via;

our @events;
my $path = "perlio_via_self_owner_$$.tmp";

package Local::OwnerVia;

sub PUSHED {
    push @main::events, 'PUSHED';
    return bless {}, $_[0];
}

sub WRITE {
    print { $_[2] } $_[1];
    return length($_[1]);
}

sub POPPED {
    push @main::events, 'POPPED';
    return 1;
}

sub DESTROY {
    push @main::events, 'DESTROY';
}

package main;

open my $fh, '>:via(Local::OwnerVia)', $path or die "open via layer: $!";
is_deeply \@events, ['PUSHED'], 'the installed layer owns its PUSHED object';
print {$fh} 'x';
is_deeply \@events, ['PUSHED'], 'callbacks run before the layer releases its object';
close $fh or die "close via layer: $!";
is_deeply \@events, ['PUSHED', 'POPPED', 'DESTROY'],
    'the layer releases its object after POPPED';

unlink $path;
done_testing;
