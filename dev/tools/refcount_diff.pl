#!/usr/bin/env perl
# dev/tools/refcount_diff.pl
#
# Differential refcount inspector: runs a Perl script under both native
# `perl` and `./jperl`, captures `REFCNT` snapshots at user-marked
# checkpoints, and prints a side-by-side diff of where the two diverge.
#
# Usage:
#   dev/tools/refcount_diff.pl <script.pl>
#
# The target script must use `Internals::jperl_refcount_checkpoint($ref, $name)`
# to mark every checkpoint. On native perl this is a no-op (defined here via
# a shim). On jperl it calls our diagnostic builtin.
#
# Output: a summary of divergences per (checkpoint, object) pair.
#
# Part of Phase 0 of dev/design/refcount_alignment_plan.md.

use strict;
use warnings;
use File::Temp qw(tempfile);
use Cwd qw(abs_path);
use FindBin qw($Bin);

my $jperl = abs_path("$Bin/../../jperl");
die "jperl not found at $jperl" unless -x $jperl;

my $script = shift or die "Usage: $0 <script.pl>\n";
$script = abs_path($script);
die "script not found: $script" unless -r $script;

# Shim library: defines Internals::jperl_refcount_checkpoint for native perl
# that uses B::svref_2object to snapshot REFCNT and record per-checkpoint state.
# On jperl, we use the already-provided Internals::jperl_refstate.
my $shim = <<'PERL';
BEGIN {
    package Internals::RefcountDiff::Shim;
    use strict;
    our @log;

    my $is_jperl = defined &Internals::jperl_refstate_str;

    sub Internals::jperl_refcount_checkpoint {
        my ($ref, $name) = @_;
        my $id = defined $ref ? ($ref + 0) : 'undef';
        my $state;
        if ($is_jperl) {
            $state = Internals::jperl_refstate_str($ref);
        } else {
            # Native perl: build equivalent state string via B::
            require B;
            if (!defined $ref) {
                $state = 'NOT_REF';
            } else {
                my $sv = B::svref_2object($ref);
                my $type = ref($ref) || 'SCALAR';
                my $class_name = '';
                if (ref($ref) && !grep { $type eq $_ } qw(SCALAR ARRAY HASH CODE GLOB REF)) {
                    $class_name = $type;
                    $type = Scalar::Util::reftype($ref) || 'SCALAR';
                }
                # Map to our kind taxonomy
                my %kind = (HASH=>'HASH', ARRAY=>'ARRAY', CODE=>'CODE',
                            GLOB=>'GLOB', SCALAR=>'SCALAR', REF=>'SCALAR');
                my $kind = $kind{$type} // 'OTHER';
                # Native Perl REFCNT: subtract 1 because our diagnostic
                # counts counted containers (not the raw SV refcount which
                # includes the passed-in ref itself).
                my $rc = $sv->REFCNT - 1;
                $state = "$kind:$class_name:$rc:";
            }
        }
        push @log, {
            checkpoint => $name,
            id         => $id,
            state      => $state,
        };
    }

    END {
        for my $entry (@log) {
            print STDOUT "REFCOUNT_DIFF $entry->{checkpoint} $entry->{id} $entry->{state}\n";
        }
    }
}
use Scalar::Util ();
PERL

# Prepend shim + load test script
my ($fh, $combined) = tempfile(SUFFIX => '.pl', UNLINK => 1);
print $fh $shim;
print $fh "\n# --- begin user script ---\n";
open my $src, '<', $script or die "open $script: $!";
print $fh $_ while <$src>;
close $src;
close $fh;

sub run_and_parse {
    my ($cmd_prefix) = @_;
    my @cmd = (@$cmd_prefix, $combined);
    open my $p, '-|', @cmd or die "fork: $!";
    # List of {checkpoint => ..., state => ...}, ordered by call sequence.
    # We intentionally DO NOT compare by refaddr because addresses differ
    # across runs; instead we compare by position in the checkpoint stream.
    my @events;
    my @other;
    while (<$p>) {
        if (/^REFCOUNT_DIFF (\S+) (\S+) (.*)$/) {
            push @events, { checkpoint => $1, id => $2, state => $3 };
        } else {
            push @other, $_;
        }
    }
    close $p;
    return { events => \@events, other => \@other };
}

print "# Running under native perl ...\n";
my $perl_result = run_and_parse(['perl']);
print "# Running under jperl ...\n";
my $jperl_result = run_and_parse([$jperl]);

# Stream comparison: match events in order. Also maintain a per-id
# remap so we can correlate re-appearances of the same address across
# runs (by stream position).
my @perl_events  = @{ $perl_result->{events}  };
my @jperl_events = @{ $jperl_result->{events} };

my $divergences = 0;
my $matches = 0;
my $n = @perl_events > @jperl_events ? @perl_events : @jperl_events;
for (my $i = 0; $i < $n; $i++) {
    my $pe = $perl_events[$i];
    my $je = $jperl_events[$i];
    if (!$pe || !$je) {
        $divergences++;
        my $cp = $pe ? $pe->{checkpoint} : $je->{checkpoint};
        my $ps = $pe ? $pe->{state} : '(no event)';
        my $js = $je ? $je->{state} : '(no event)';
        printf("DIVERGE  #%d %-30s  perl=%s  jperl=%s\n", $i, $cp, $ps, $js);
        next;
    }
    if ($pe->{checkpoint} ne $je->{checkpoint}) {
        $divergences++;
        printf("CHECKPOINT-MISMATCH #%d perl=%s  jperl=%s\n",
               $i, $pe->{checkpoint}, $je->{checkpoint});
        next;
    }
    if ($pe->{state} eq $je->{state}) {
        $matches++;
    } else {
        $divergences++;
        printf("DIVERGE  #%d %-30s  perl=%s  jperl=%s\n",
               $i, $pe->{checkpoint}, $pe->{state}, $je->{state});
    }
}

print "\n";
print "# Matches:     $matches\n";
print "# Divergences: $divergences\n";
exit($divergences > 0 ? 1 : 0);
