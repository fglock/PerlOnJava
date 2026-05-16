#!/usr/bin/env perl
# Repro for DBIx::Simple chained query vs temporaries on PerlOnJava (see dev/modules/dbix_simple.md).
# Run with bundled lib first:
#   PERL5LIB="$PWD/src/main/perl/lib${PERL5LIB:+:$PERL5LIB}" timeout 120 ./jperl dev/tools/dbix_simple_chain_repro.pl

use strict;
use warnings;

use DBIx::Simple ();

my $db = DBIx::Simple->connect( 'dbi:SQLite:dbname=:memory:', '', '',
    { RaiseError => 1 } );

print STDERR "keep_statements=", $db->keep_statements, "\n";

$db->query(q{CREATE TABLE t (id INTEGER NOT NULL)});
$db->query(q{INSERT INTO t VALUES (1),(2),(3)});

my $chain_ref =
  scalar $db->query('SELECT id FROM t ORDER BY id')->arrays;

my $r = $db->query('SELECT id FROM t ORDER BY id');
my $stored_ref = scalar $r->arrays;

die "chain: expected ARRAY ref, got "
  . ( defined $chain_ref ? ref $chain_ref || 'plain scalar' : 'undef' )
  unless ref($chain_ref) eq 'ARRAY';

die "stored: expected ARRAY ref, got "
  . ( defined $stored_ref ? ref $stored_ref || 'plain scalar' : 'undef' )
  unless ref($stored_ref) eq 'ARRAY';

my $chain_n = scalar @$chain_ref;
my $stored_n = scalar @$stored_ref;

print "chain_rows=$chain_n stored_rows=$stored_n\n";

if ( $chain_n != $stored_n ) {
    die "FAIL: scalar \\\$db->query(...)->arrays row count mismatch "
      . "(stored=$stored_n, chain=$chain_n)\n";
}

print "OK\n";
