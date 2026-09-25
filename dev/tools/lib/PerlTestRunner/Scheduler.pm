package PerlTestRunner::Scheduler;

use strict;
use warnings;
use Exporter qw(import);

our @EXPORT_OK = qw(
    effective_weight
    next_runnable_index
    profile_for_test
    scheduling_priority
    duration_priority
    test_can_start
);

sub profile_for_test {
    my ($test_file) = @_;
    (my $normalized_file = $test_file) =~ tr{\\}{/};

    # This test creates and executes progtmp* files containing #!./perl. Its
    # private cwd prevents cross-runner races, so it can use the normal
    # resource-aware scheduler like every other test.
    if ($normalized_file =~ m{(?:^|/)(?:perl5_t/t/)?japh/abigail\.t$}) {
        return {
            class => 'heavy',
            weight => 3,
        };
    }

    # pat_thr.t executes the complete pat.t corpus inside a Perl thread.  Its
    # snapshot plus the regex corpus pressure consumes the whole supported
    # ten-unit production budget; smaller caller budgets clamp this weight so
    # the fixture still makes progress without overlapping other work.
    if ($normalized_file =~ m{(?:^|/)(?:perl5_t/t/)?re/pat_thr\.t$}) {
        return {
            class => 'heavy',
            weight => 10,
        };
    }

    # Direct pat and anyof runs sustain enough allocation/GC pressure that at
    # most two should share a ten-unit budget.
    if ($normalized_file =~ m{
          (?:^|/)(?:perl5_t/t/)?re/pat\.t$
        | (?:^|/)(?:perl5_t/t/)?re/anyof(?:_thr)?\.t$
    }x) {
        return {
            class => 'heavy',
            weight => 5,
        };
    }

    # These fixtures create sustained CPU, memory, or subprocess pressure.
    # Weight three permits three such files within a --jobs 10 budget while
    # leaving one unit available for an ordinary test.
    if ($normalized_file =~ m{
          (?:^|/)perl5/dist/threads/t/join\.t$
        | (?:^|/)(?:perl5_t/t/)?op/gv\.t$
        | (?:^|/)(?:perl5_t/t/)?re/pat_psycho(?:_thr)?\.t$
        | (?:^|/)(?:perl5_t/t/)?re/pat_advanced(?:_thr)?\.t$
        | (?:^|/)(?:perl5_t/t/)?re/regexp_qr_embed_thr\.t$
        | (?:^|/)(?:perl5_t/t/)?re/speed(?:_thr)?\.t$
    }x) {
        return {
            class => 'heavy',
            weight => 3,
        };
    }

    # Timing-only benchmarks deliberately receive no semantic scheduling
    # privilege; authoritative timings use a separate controlled procedure.
    return {
        class => 'normal',
        weight => 1,
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
    return 1 if ($profile->{weight} || 1) > 1;
    return 2;
}

sub duration_priority {
    my ($test_file) = @_;
    (my $normalized_file = $test_file) =~ tr{\\}{/};

    # Keep the known longest fixtures at the head of their resource class.
    # anyof is the slowest compatibility test by a wide margin; putting it
    # first lets it overlap with shorter work instead of becoming the final
    # straggler.  The remaining entries are ordered by their observed runtime
    # tiers, while unknown tests retain their discovery order.
    return 0 if $normalized_file =~ m{(?:^|/)(?:perl5_t/t/)?re/anyof(?:_thr)?\.t$};
    return 1 if $normalized_file =~ m{(?:^|/)(?:perl5_t/t/)?re/pat_thr\.t$};
    return 2 if $normalized_file =~ m{(?:^|/)(?:perl5_t/t/)?re/pat\.t$};
    return 3 if $normalized_file =~ m{
          (?:^|/)perl5/dist/threads/t/join\.t$
        | (?:^|/)(?:perl5_t/t/)?op/gv\.t$
        | (?:^|/)(?:perl5_t/t/)?re/pat_(?:psycho|advanced)(?:_thr)?\.t$
        | (?:^|/)(?:perl5_t/t/)?re/regexp_qr_embed_thr\.t$
        | (?:^|/)(?:perl5_t/t/)?re/speed(?:_thr)?\.t$
    }x;
    return 100;
}

sub test_can_start {
    my ($profile, $budget, $active_weight, $active_count) = @_;

    my $weight = effective_weight($profile, $budget);
    return $active_weight + $weight <= $budget;
}

sub next_runnable_index {
    my ($tests, $budget, $active_weight, $active_count) = @_;
    for my $index (0 .. $#$tests) {
        my $profile = $tests->[$index]{profile};

        return $index if test_can_start(
            $profile,
            $budget,
            $active_weight,
            $active_count,
        );
    }
    return;
}

1;
