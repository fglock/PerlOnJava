use strict;
use warnings;
use Test::More;

my @events;

sub leave_map_loop {
    no warnings 'exiting';
    last MAP_LOOP;
}
MAP_LOOP: for my $i (1 .. 2) {
    push @events, "map-start-$i";
    my @values = map { leave_map_loop() if $_ == 1; $_ } 1 .. 2;
    push @events, "map-done-$i";
}
is_deeply(\@events, ['map-start-1'], 'last from a sub in map leaves the enclosing loop');

@events = ();
sub leave_grep_loop {
    no warnings 'exiting';
    last GREP_LOOP;
}
GREP_LOOP: for my $i (1 .. 2) {
    push @events, "grep-start-$i";
    my @values = grep { leave_grep_loop() if $_ == 1; 1 } 1 .. 2;
    push @events, "grep-done-$i";
}
is_deeply(\@events, ['grep-start-1'], 'last from a sub in grep leaves the enclosing loop');

@events = ();
sub next_map_loop {
    no warnings 'exiting';
    next MAP_NEXT_LOOP;
}
MAP_NEXT_LOOP: for my $i (1 .. 2) {
    push @events, "map-start-$i";
    my @values = map { next_map_loop() if $_ == 1; $_ } 1 .. 2;
    push @events, "map-done-$i";
}
is_deeply(\@events, ['map-start-1', 'map-start-2'], 'next from a sub in map advances the enclosing loop');

@events = ();
sub next_grep_loop {
    no warnings 'exiting';
    next GREP_NEXT_LOOP;
}
GREP_NEXT_LOOP: for my $i (1 .. 2) {
    push @events, "grep-start-$i";
    my @values = grep { next_grep_loop() if $_ == 1; 1 } 1 .. 2;
    push @events, "grep-done-$i";
}
is_deeply(\@events, ['grep-start-1', 'grep-start-2'], 'next from a sub in grep advances the enclosing loop');

@events = ();
our $map_redo_count = 0;
sub redo_map_loop {
    no warnings 'exiting';
    redo MAP_REDO_LOOP if ++$map_redo_count == 1;
}
MAP_REDO_LOOP: for my $i (1 .. 2) {
    push @events, "map-start-$i";
    my @values = map { redo_map_loop() if $_ == 1; $_ } 1 .. 2;
    push @events, "map-done-$i";
}
is_deeply(\@events, ['map-start-1', 'map-start-1', 'map-done-1', 'map-start-2', 'map-done-2'],
    'redo from a sub in map restarts the enclosing loop');

@events = ();
our $grep_redo_count = 0;
sub redo_grep_loop {
    no warnings 'exiting';
    redo GREP_REDO_LOOP if ++$grep_redo_count == 1;
}
GREP_REDO_LOOP: for my $i (1 .. 2) {
    push @events, "grep-start-$i";
    my @values = grep { redo_grep_loop() if $_ == 1; 1 } 1 .. 2;
    push @events, "grep-done-$i";
}
is_deeply(\@events, ['grep-start-1', 'grep-start-1', 'grep-done-1', 'grep-start-2', 'grep-done-2'],
    'redo from a sub in grep restarts the enclosing loop');

done_testing();
