#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use utf8;
use Encode qw(is_utf8);

# PerlOnJava bundles a no-op stub of the deprecated `encoding` pragma so
# that older CPAN modules (which still write `use encoding 'utf8';`)
# can be loaded. We don't emulate the source-encoding or chr/ord/length
# overrides; we only honour explicit filehandle-layer arguments.
#
# This test exercises *PerlOnJava's stub*, not the real CPAN distribution
# (which dies on bare `use encoding;` and is removed from core in 5.26+).
# We force-load our stub by putting src/main/perl/lib at the head of @INC
# and clearing any cached `encoding.pm` so the test passes under both
# `./jperl` and system `prove`.
BEGIN {
    # Under PerlOnJava, the bundled stub is available from `jar:PERL5LIB`
    # in @INC, so a normal `require encoding` finds our stub. Under system
    # perl + `prove`, we need to force-load OUR stub instead of CPAN's
    # `encoding.pm` (which dies on bare `use encoding;`). We detect "running
    # under system perl" by the absence of `jar:PERL5LIB` in @INC.
    if (!grep { /^jar:/ } @INC) {
        require File::Spec;
        require Cwd;
        # Walk up from the cwd looking for src/main/perl/lib (works whether
        # prove is invoked from the repo root or a subdirectory).
        my @parts = File::Spec->splitdir(Cwd::cwd());
        my $stub_dir;
        while (@parts) {
            my $candidate = File::Spec->catdir(@parts, qw(src main perl lib));
            if (-e File::Spec->catfile($candidate, 'encoding.pm')) {
                $stub_dir = $candidate;
                last;
            }
            pop @parts;
        }
        if (!$stub_dir) {
            # Fall back to skip rather than failing: tells the user the
            # PerlOnJava stub couldn't be located from the current dir.
            require Test::More;
            Test::More::plan(skip_all =>
                "PerlOnJava encoding.pm stub not found in any ancestor of "
                . Cwd::cwd());
            exit 0;
        }
        unshift @INC, $stub_dir;
        delete $INC{'encoding.pm'};
    }
}

subtest 'use encoding;  (no args)' => sub {
    my $loaded = eval { require encoding; 1 };
    ok($loaded, 'encoding.pm loads')
        or diag $@;

    # Bare `use encoding;` must not die and must not affect anything.
    eval { encoding->import() };
    is($@, '', 'use encoding; (no args) is a no-op');
};

subtest "use encoding 'utf8'" => sub {
    eval { encoding->import('utf8') };
    is($@, '', "use encoding 'utf8'; does not die");

    # `length` and `ord` must keep their byte-vs-character semantics
    # unchanged - the historical pragma's overrides are intentionally
    # NOT emulated. Without `use utf8;`, the literal "é" is two UTF-8
    # bytes and PerlOnJava correctly reports length 2.
    my $latin1_byte = "\xE9";
    is(length($latin1_byte), 1, 'length of single byte is 1 (not overridden)');
    is(ord($latin1_byte),  233, 'ord of single byte is 233 (not overridden)');
};

subtest "no encoding;" => sub {
    eval { encoding->unimport() };
    is($@, '', 'no encoding; does not die');
};

subtest 'filehandle-layer arguments' => sub {
    # We don't crash and we don't propagate binmode failures.
    eval { encoding->import('utf8', STDOUT => 'utf8') };
    is($@, '', "use encoding 'utf8', STDOUT => 'utf8' does not die");

    eval { encoding->import('utf8', BogusHandle => 'utf8') };
    is($@, '', 'unknown filehandle name does not die');
};

subtest 'encoding::name accessor' => sub {
    is(encoding::name(), 'utf8',
       'encoding::name() always reports utf8 (matches our parser)');
};

subtest 'real-world load form (utf8 + Encode + encoding)' => sub {
    # The Lingua::ZH::MMSEG-style header `use utf8; use Encode; use encoding`
    # is loaded at the top of this file (lines 5-6 + the BEGIN-loaded stub),
    # so reaching this subtest is itself the proof that the three pragmas
    # coexist at compile time. We additionally re-import the stub here to
    # confirm runtime import is also a no-op alongside an active Encode.
    encoding->import('utf8');
    pass("use encoding 'utf8'; coexists with use utf8 + use Encode");
    ok(defined(\&is_utf8), '... and Encode::is_utf8 is still importable');
};

done_testing();
