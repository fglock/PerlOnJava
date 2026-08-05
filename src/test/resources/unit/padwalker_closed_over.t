use strict;
use warnings;
use Test::More tests => 7;
use PadWalker qw(closed_over);

my $scalar = 'captured';
my @array = qw(a b);
my %hash = (key => 'value');
my $closure = sub { return ($scalar, @array, %hash) };
my $closed = closed_over($closure);

ok(exists $closed->{'$scalar'}, 'closed_over reports a captured scalar');
ok(exists $closed->{'@array'}, 'closed_over reports a captured array');
ok(exists $closed->{'%hash'}, 'closed_over reports a captured hash');
is(${ $closed->{'$scalar'} }, 'captured', 'captured scalar reference is live');
is_deeply($closed->{'@array'}, \@array, 'captured array reference aliases the pad');

my $named_value = 1;
sub named_closure { ++$named_value }
my $named_closed = closed_over(\&named_closure);
is(${ $named_closed->{'$named_value'} }, 1, 'closed_over reports a named-sub lexical');
named_closure();
is(${ $named_closed->{'$named_value'} }, 2, 'named-sub lexical reference remains live');
