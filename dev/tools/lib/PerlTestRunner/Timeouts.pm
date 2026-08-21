package PerlTestRunner::Timeouts;

use strict;
use warnings;
use Exporter qw(import);

our @EXPORT_OK = qw(timeout_for_test);

sub timeout_for_test {
    my ($test_file, $base_timeout) = @_;
    die "Base timeout must be positive\n"
        unless defined($base_timeout) && $base_timeout > 0;

    (my $normalized_file = $test_file) =~ tr{\\}{/};

    # The through-pipe matrix starts hundreds of child processes and performs
    # blocking reads for each read/write combination. Keep custom timeouts
    # proportional for these two fixtures.
    return $base_timeout * 2
        if $normalized_file =~ m{(?:^|/)perl5_t/t/io/(?:crlf_)?through\.t$};

    # Complete anyof maps take roughly 1,125 seconds even when isolated. The
    # floor is a watchdog, not a performance target, and preserves any larger
    # timeout supplied by the caller.
    return 1800
        if $normalized_file =~ m{(?:^|/)perl5_t/t/re/anyof(?:_thr)?\.t$}
        && $base_timeout < 1800;

    # A ten-worker production-load acceptance can push pat beyond the default
    # deadline. Resource-aware scheduling isolates pat_thr separately.
    return 900
        if $normalized_file =~ m{(?:^|/)perl5_t/t/re/pat(?:_thr)?\.t$}
        && $base_timeout < 900;

    return 600 if $normalized_file =~ m{
          (?:^|/)perl5_t/t/lib/croak\.t$
        | (?:^|/)perl5_t/t/re/pat_psycho(?:_thr)?\.t$
        | (?:^|/)perl5_t/t/op/gv\.t$
        | (?:^|/)perl5_t/t/re/pat_advanced(?:_thr)?\.t$
        | (?:^|/)perl5_t/t/re/regexp_qr_embed_thr\.t$
        | (?:^|/)perl5_t/t/re/speed(?:_thr)?\.t$
        | (?:^|/)perl5_t/t/japh/abigail\.t$
    }x && $base_timeout < 600;

    return $base_timeout;
}

1;
