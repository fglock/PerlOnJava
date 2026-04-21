#!/usr/bin/env perl
# Net::SSLeay symbol inventory baseline test.
#
# Reads dev/modules/netssleay_symbols.tsv and validates each row against
# the live module. The test is the regression gate for the complete-
# implementation work tracked in dev/modules/netssleay_complete.md —
# any new STUB or MISSING entry without a phase, or a DONE entry that
# stops being defined, fails this test.
use strict;
use warnings;
use Test::More;
use Net::SSLeay ();

my $tsv = "dev/modules/netssleay_symbols.tsv";
unless (-e $tsv) {
    plan skip_all => "inventory TSV not found ($tsv); run from repo root";
}

open my $fh, "<", $tsv or die "$tsv: $!";
my @rows;
while (my $line = <$fh>) {
    chomp $line;
    next if $line =~ /^\s*#/;
    next if $line =~ /^\s*$/;
    next if $line =~ /^name\t/;
    my ($name, $kind, $impl, $phase, $notes) = split /\t/, $line, 5;
    $notes //= "";
    push @rows, { name => $name, kind => $kind, impl => $impl,
                  phase => $phase, notes => $notes };
}
close $fh;

ok( @rows > 500, "TSV has at least 500 entries (" . scalar(@rows) . ")" );

# -- column validity --
my %valid_kind = map { $_ => 1 } qw(constant method lambda missing);
my %valid_impl = map { $_ => 1 } qw(DONE PARTIAL STUB MISSING);
my %valid_phase = map { $_ => 1 } qw(0 1 2 3 4 5 6 7 8);

for my $r (@rows) {
    ok($valid_kind{ $r->{kind} },
        "row '$r->{name}' has a valid kind ('$r->{kind}')");
    ok($valid_impl{ $r->{impl} },
        "row '$r->{name}' has a valid impl ('$r->{impl}')");
    ok($valid_phase{ $r->{phase} },
        "row '$r->{name}' has a valid phase ('$r->{phase}')");
}

# -- implementation status vs live module --
# We only enforce:
#  1. DONE rows must resolve to a callable (constant or sub).
#  2. MISSING rows must NOT be registered as subs (otherwise the TSV
#     is out of date or the stub should have been tracked).
# STUB and PARTIAL rows are not checked because they're allowed to be
# in either state during the phased rollout — the inventory is the
# authoritative record while Phases 1–8 land.

my %defined_sub;
{
    # Walk the Net::SSLeay:: stash and collect defined CODE slots.
    no strict 'refs';
    for my $sym (keys %Net::SSLeay::) {
        my $glob = $Net::SSLeay::{$sym};
        next unless defined $glob;
        # Handle both CODE refs and typeglobs
        if (ref \$glob eq "GLOB" && defined *{$glob}{CODE}) {
            $defined_sub{$sym} = 1;
        } elsif (ref $glob eq "CODE") {
            $defined_sub{$sym} = 1;
        }
    }
}

my %constant_in_eval;
sub constant_exists {
    my $name = shift;
    return $constant_in_eval{$name} //= eval {
        Net::SSLeay::constant($name);
        1;
    } || 0;
}

for my $r (@rows) {
    if ($r->{impl} eq "DONE") {
        if ($r->{kind} eq "constant") {
            ok( constant_exists($r->{name}),
                "DONE constant '$r->{name}' is resolvable via Net::SSLeay::constant" );
        } else {
            ok( $defined_sub{$r->{name}},
                "DONE sub '$r->{name}' is defined in Net::SSLeay" );
        }
    } elsif ($r->{impl} eq "MISSING") {
        ok( !$defined_sub{$r->{name}},
            "MISSING sub '$r->{name}' is not registered (inventory up to date)" );
    }
}

# -- overall scoreboard --
my %counts;
$counts{ $_->{impl} }++ for @rows;
diag sprintf("inventory: DONE=%d PARTIAL=%d STUB=%d MISSING=%d",
             $counts{DONE}    // 0,
             $counts{PARTIAL} // 0,
             $counts{STUB}    // 0,
             $counts{MISSING} // 0);

done_testing();
