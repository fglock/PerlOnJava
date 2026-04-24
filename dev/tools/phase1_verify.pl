#!/usr/bin/env perl
# dev/tools/phase1_verify.pl
#
# Phase 1 verification: Complete scope-exit decrement for scalar lexicals.
# Runs a series of refcount-delta test cases via dev/tools/refcount_diff.pl
# and reports the result.
#
# Each test is a self-contained Perl script that marks refcount checkpoints.
# The test passes if native `perl` and `./jperl` agree on all checkpoints.

use strict;
use warnings;
use FindBin qw($Bin);
use File::Temp qw(tempfile);

my $refcount_diff = "$Bin/refcount_diff.pl";
die "missing $refcount_diff" unless -x $refcount_diff;

my @cases = (
    {
        name => 'scalar assignment',
        code => q|
            my $arr = [1,2,3];
            Internals::jperl_refcount_checkpoint($arr, "create");
            { my $ref = $arr;
              Internals::jperl_refcount_checkpoint($arr, "in_inner"); }
            Internals::jperl_refcount_checkpoint($arr, "after_inner");
        |,
    },
    {
        name => 'shift in sub',
        code => q|
            sub test { my $o = shift; Internals::jperl_refcount_checkpoint($o, "inside"); }
            my $x = [1,2,3];
            Internals::jperl_refcount_checkpoint($x, "before");
            test($x);
            Internals::jperl_refcount_checkpoint($x, "after");
        |,
    },
    {
        name => 'closure capture',
        code => q|
            my $x = [1,2,3];
            Internals::jperl_refcount_checkpoint($x, "before_closure");
            my $c = sub { $x };
            Internals::jperl_refcount_checkpoint($x, "after_closure");
            my $got = $c->();
            Internals::jperl_refcount_checkpoint($x, "after_call");
        |,
    },
    {
        name => 'hash store/delete',
        code => q|
            my $x = [1,2,3];
            Internals::jperl_refcount_checkpoint($x, "before");
            my %h; $h{k} = $x;
            Internals::jperl_refcount_checkpoint($x, "stored");
            delete $h{k};
            Internals::jperl_refcount_checkpoint($x, "deleted");
        |,
    },
    {
        name => 'array store/clear',
        code => q|
            my $x = [1,2,3];
            Internals::jperl_refcount_checkpoint($x, "before");
            my @a; push @a, $x;
            Internals::jperl_refcount_checkpoint($x, "pushed");
            @a = ();
            Internals::jperl_refcount_checkpoint($x, "cleared");
        |,
    },
    {
        name => 'for loop',
        code => q|
            my @refs = map { [$_] } 1..3;
            my $last;
            for my $r (@refs) { $last = $r;
                Internals::jperl_refcount_checkpoint($r, "inloop"); }
            Internals::jperl_refcount_checkpoint($last, "after");
        |,
    },
    {
        name => 'return value',
        code => q|
            sub make { return [1,2,3] }
            my $r = make();
            Internals::jperl_refcount_checkpoint($r, "captured_return");
            { my $r2 = make();
              Internals::jperl_refcount_checkpoint($r2, "inner_return"); }
        |,
    },
    {
        name => 'do block return',
        code => q|
            my $r = do {
                my $arr = [1,2,3];
                Internals::jperl_refcount_checkpoint($arr, "inside_do");
                $arr;
            };
            Internals::jperl_refcount_checkpoint($r, "after_do");
        |,
    },
    {
        name => 'method chain',
        code => q|
            package MyClass;
            sub new { bless { arr => [1,2,3] }, shift }
            sub get_arr { shift->{arr} }
            package main;
            my $obj = MyClass->new;
            my $arr = $obj->get_arr;
            Internals::jperl_refcount_checkpoint($arr, "after_method");
        |,
    },
    {
        name => 'recursive call',
        code => q|
            sub recurse {
                my ($r, $d) = @_;
                return if $d == 0;
                Internals::jperl_refcount_checkpoint($r, "depth_$d");
                recurse($r, $d - 1);
            }
            my $x = [1,2,3];
            recurse($x, 3);
            Internals::jperl_refcount_checkpoint($x, "after_recurse");
        |,
    },
);

my $total = 0;
my $passed = 0;
my $failed = 0;

for my $case (@cases) {
    my ($fh, $tmp) = tempfile(SUFFIX=>'.pl', UNLINK=>1);
    print $fh $case->{code};
    close $fh;
    my $out = `$refcount_diff $tmp 2>&1`;
    my $exit = $? >> 8;
    $total++;
    if ($exit == 0) {
        $passed++;
        printf("PASS  %-30s\n", $case->{name});
    } else {
        $failed++;
        printf("FAIL  %-30s\n", $case->{name});
        for my $line (split /\n/, $out) {
            print "      $line\n" if $line =~ /DIVERGE|CHECKPOINT-MISMATCH/;
        }
    }
}

print "\n";
printf("Passed: %d/%d\n", $passed, $total);
exit($failed > 0 ? 1 : 0);
