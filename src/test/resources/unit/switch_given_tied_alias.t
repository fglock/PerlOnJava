use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

{
    package SwitchTiedCounter;
    sub TIESCALAR { bless { value => undef, fetches => 0 }, shift }
    sub STORE { $_[0]->{fetches} = 0; $_[0]->{value} = $_[1] }
    sub FETCH { $_[0]->{fetches}++; $_[0]->{value} }
    sub fetches { $_[0]->{fetches} }
}

my $foreach_counter = tie my $foreach_value, 'SwitchTiedCounter';
for $_ ($foreach_value = 23) {
    my $first = $_;
    my $second = $_;
    my $third = $_;
}
is $foreach_counter->fetches, 3,
    'a topicalizing foreach aliases an assignable tied source';

my $counter = tie my $value, 'SwitchTiedCounter';
my $matched;
given ($value = 23) {
    when (undef) {}
    when (21) {}
    when (23) { $matched = 1 }
    when (/24/) { $matched = 0 }
}

is $matched, 1, 'given retains the tied topic value';
is $counter->fetches, 3, 'given aliases an assignable tied topic for each when';

done_testing;
