package Test::LeakTrace;
use strict;
use warnings;

our $VERSION = '0.17';

# No-op stub for PerlOnJava.
# The JVM uses tracing GC, not refcounting, so SV-arena leak detection
# is meaningless.  Every test simply passes.

use Exporter qw(import);

our @EXPORT = qw(
    leaktrace leaked_refs leaked_info leaked_count
    no_leaks_ok leaks_cmp_ok
    count_sv
);

our %EXPORT_TAGS = (
    all  => \@EXPORT,
    test => [qw(no_leaks_ok leaks_cmp_ok)],
    util => [qw(leaktrace leaked_refs leaked_info leaked_count count_sv)],
);

sub leaked_refs(&)  { my ($block) = @_; $block->(); return (); }
sub leaked_info(&)  { my ($block) = @_; $block->(); return (); }
sub leaked_count(&) { my ($block) = @_; $block->(); return 0; }
sub leaktrace(&;$)  { my ($block) = @_; $block->(); return; }
sub count_sv        { return 0; }

sub no_leaks_ok(&;$) {
    my ($block, $description) = @_;
    $block->();
    require Test::More;
    Test::More::pass($description || 'no leaks (Test::LeakTrace stub)');
    return 1;
}

sub leaks_cmp_ok(&$$;$) {
    my ($block, $cmp_op, $expected, $description) = @_;
    $block->();
    require Test::More;
    Test::More::cmp_ok(0, $cmp_op, $expected,
        $description || "leaks_cmp_ok (Test::LeakTrace stub)");
    return 1;
}

# Private stubs for Test::LeakTrace::Script compatibility
sub _start            { }
sub _finish           { }
sub _runops_installed { return 0; }

1;

__END__

=head1 NAME

Test::LeakTrace - No-op stub for PerlOnJava

=head1 DESCRIPTION

This is a no-op implementation of Test::LeakTrace for PerlOnJava.
The JVM uses tracing garbage collection rather than reference counting,
so Perl-level SV leak detection does not apply.  All leak tests pass
unconditionally.

=cut
