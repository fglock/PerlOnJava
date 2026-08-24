package PerlTestRunner::Scheduler;

use strict;
use warnings;
use Exporter qw(import);

our @EXPORT_OK = qw(
    effective_weight
    next_runnable_index
    profile_for_test
    scheduling_priority
    test_can_start
);

sub profile_for_test {
    my ($test_file) = @_;
    (my $normalized_file = $test_file) =~ tr{\\}{/};

    # This test creates and executes progtmp* files containing #!./perl. Its
    # private cwd prevents cross-runner races; an exclusive barrier also keeps
    # it from competing with other work inside one resource-aware runner.
    if ($normalized_file =~ m{(?:^|/)perl5_t/t/japh/abigail\.t$}) {
        return {
            class => 'exclusive',
            weight => 3,
            exclusive => 1,
        };
    }

    # pat_thr.t executes the complete pat.t corpus inside a Perl thread.  Its
    # snapshot plus the regex corpus pressure consumes the whole supported
    # ten-unit production budget; smaller caller budgets clamp this weight so
    # the fixture still makes progress without overlapping other work.
    if ($normalized_file =~ m{(?:^|/)perl5_t/t/re/pat_thr\.t$}) {
        return {
            class => 'heavy',
            weight => 10,
            exclusive => 0,
        };
    }

    # Direct pat and anyof runs sustain enough allocation/GC pressure that at
    # most two should share a ten-unit budget.  Keeping these non-exclusive
    # lets the runner use the other half for the complementary direct gate.
    if ($normalized_file =~ m{
          (?:^|/)perl5_t/t/re/pat\.t$
        | (?:^|/)perl5_t/t/re/anyof(?:_thr)?\.t$
    }x) {
        return {
            class => 'heavy',
            weight => 5,
            exclusive => 0,
        };
    }

    # These fixtures create sustained CPU, memory, or subprocess pressure.
    # Weight three permits three such files within a --jobs 10 budget while
    # leaving one unit available for an ordinary test.
    if ($normalized_file =~ m{
          (?:^|/)perl5/dist/threads/t/join\.t$
        | (?:^|/)perl5_t/t/op/gv\.t$
        | (?:^|/)perl5_t/t/re/pat_psycho(?:_thr)?\.t$
        | (?:^|/)perl5_t/t/re/pat_advanced(?:_thr)?\.t$
        | (?:^|/)perl5_t/t/re/regexp_qr_embed_thr\.t$
        | (?:^|/)perl5_t/t/re/speed(?:_thr)?\.t$
    }x) {
        return {
            class => 'heavy',
            weight => 3,
            exclusive => 0,
        };
    }

    # Timing-only benchmarks deliberately receive no semantic scheduling
    # privilege; authoritative timings use a separate controlled procedure.
    return {
        class => 'normal',
        weight => 1,
        exclusive => 0,
    };
}

sub effective_weight {
    my ($profile, $budget) = @_;
    die "Scheduling budget must be positive\n"
        unless defined($budget) && $budget > 0;

    my $weight = $profile->{weight} || 1;
    return $weight > $budget ? $budget : $weight;
}

sub scheduling_priority {
    my ($profile) = @_;
    return 0 if $profile->{exclusive};
    return 1 if ($profile->{weight} || 1) > 1;
    return 2;
}

sub test_can_start {
    my ($profile, $budget, $active_weight, $active_count, $exclusive_active) = @_;

    return 0 if $exclusive_active;
    return $active_count == 0 if $profile->{exclusive};

    my $weight = effective_weight($profile, $budget);
    return $active_weight + $weight <= $budget;
}

sub next_runnable_index {
    my ($tests, $budget, $active_weight, $active_count, $exclusive_active) = @_;

    return if $exclusive_active;
    for my $index (0 .. $#$tests) {
        my $profile = $tests->[$index]{profile};

        # An isolation case is a barrier: it may start only when the runner is
        # idle, and later work must never leapfrog it.
        if ($profile->{exclusive}) {
            return $active_count == 0 ? $index : undef;
        }

        return $index if test_can_start(
            $profile,
            $budget,
            $active_weight,
            $active_count,
            $exclusive_active,
        );
    }
    return;
}

1;
