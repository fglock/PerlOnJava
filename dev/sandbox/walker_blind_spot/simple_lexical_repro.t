#!/usr/bin/env perl
# Minimal reproducer for the ReachabilityWalker blind spot in
# MortalList.maybeAutoSweep() — the bug that breaks DBIx::Class
# t/52leaks.t under jcpan -t DBIx::Class.
#
# Pattern (matches DBIC's Schema ↔ ResultSource ↔ Row chain):
#
#   my $schema = ...;                      # strong ref in main lexical
#   my $rs = My::ResultSource->new($schema);
#   $rs->{schema}  -> weakened reference back to $schema
#
# At every Perl statement boundary, MortalList.flush() may invoke
# maybeAutoSweep() (5-s throttle, fires once `weakRefsExist` is true).
# That sweep walks reachable objects from globals and lexicals, then
# clears weak refs to unreachable ones.
#
# Since $schema lives in a `my` slot held strongly by the running
# main scope, it is reachable. The bug: ReachabilityWalker fails
# to seed the live lexical as a root, so the schema is classified
# unreachable, and the weak ref from $rs->{schema} is cleared.

use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

package My::Schema;
sub new { bless { name => 'main schema' }, shift }

package My::ResultSource;
use Scalar::Util qw(weaken);
sub new {
    my ($class, $schema) = @_;
    my $self = bless { schema => $schema }, $class;
    weaken $self->{schema};
    return $self;
}
sub schema {
    my $self = shift;
    return $self->{schema}
        || die "detached: weak ref to schema cleared\n";
}

package main;

my $schema = My::Schema->new;
my $rs = My::ResultSource->new($schema);

ok( $rs->schema, 'weak ref intact at t=0' );

# Burn > 5 s of wall clock at statement boundaries so
# MortalList.maybeAutoSweep() definitely fires at least once.
# Each iteration is a separate statement → triggers flush → may sweep.
my $deadline = time() + 7;
my $iterations = 0;
while ( time() < $deadline ) {
    $iterations++;
    my @junk = ( 1 .. 50 );
    my %junk = ( a => 1, b => 2 );
}

# After auto-sweep should have fired several times, the weak ref MUST
# still resolve — `$schema` is still strongly held in our main scope.
my $s;
my $err;
eval { $s = $rs->schema; 1 } or $err = $@;
ok( !$err,                                  "schema still reachable after $iterations iterations + auto-sweep" )
    or diag "got error: $err";
is( defined($s) ? $s->{name} : '<undef>',
    'main schema',
    'schema content preserved' );

# Sanity: $schema lexical itself is still defined (no compiler issue)
ok( defined $schema, '$schema lexical itself still defined' );

done_testing;
