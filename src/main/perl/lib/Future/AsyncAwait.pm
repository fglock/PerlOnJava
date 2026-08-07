package Future::AsyncAwait;

use v5.14;
use warnings;

our $VERSION = '0.71';

# Match upstream's default Future implementation when it is installed. Keep
# loading non-fatal so the compiler can emit its targeted capability message
# in minimal environments that only contain this syntax facade.
eval {
    require Future;
    Future->VERSION('0.49');
    1;
};

# Upstream exposes this test helper as the current Perl context-stack index.
# PerlOnJava's resumable frames are heap-owned rather than Perl CX stack
# entries, so the externally useful invariant is represented by a stable zero.
sub __cxstack_ix () { 0 }

# PerlOnJava implements the syntax in its own frontend. Keep activation in
# %^H so it follows Perl's normal lexical pragma scoping rules.
sub import {
    my $class = shift;
    $^H |= 0x020000;
    $^H{'Future::AsyncAwait/async'} = 1;
    $^H{'Future::AsyncAwait/awaitable'} = 1
            if $INC{'Future.pm'} || Future->can('new');
    while (@_) {
        my $option = shift;
        if ($option eq ':experimental(cancel)') {
            $^H{'Future::AsyncAwait/cancel'} = 1;
        }
        elsif ($option eq 'future_class') {
            $^H{'Future::AsyncAwait/future'} = shift;
        }
    }
}

sub unimport {
    delete $^H{'Future::AsyncAwait/async'};
    delete $^H{'Future::AsyncAwait/awaitable'};
    delete $^H{'Future::AsyncAwait/cancel'};
    delete $^H{'Future::AsyncAwait/future'};
}

1;

__END__

=head1 NAME

Future::AsyncAwait - PerlOnJava frontend compatibility facade

=head1 DESCRIPTION

This facade activates PerlOnJava's native parsing of C<async sub> and
C<await>. Async subroutines use resumable interpreter frames and support both
immediate and pending objects implementing the
C<Future::AsyncAwait::Awaitable> method contract. Load C<Future>, or another
compatible implementation, before declaring async subroutines.

File-scope C<await> invokes the Awaitable C<AWAIT_WAIT> method in the
surrounding scalar, list, or void context.

Lexical async subs use C<my async sub>. Async class methods, signatures,
attributes, forward declarations, and experimental C<CANCEL> blocks are
supported. Enable cancellation blocks with C<:experimental(cancel)>.

The public syntax and behavior are based on Paul Evans' Future::AsyncAwait.
PerlOnJava does not load or emulate its Perl-internal XS optree hooks.

=head1 AUTHOR

Paul Evans E<lt>leonerd@leonerd.org.ukE<gt>, original Future::AsyncAwait.

PerlOnJava compatibility facade maintained by the PerlOnJava contributors.

=head1 COPYRIGHT AND LICENSE

Copyright 2016-2024 Paul Evans.

This library is free software; you may redistribute it and/or modify it under
the same terms as Perl itself.

=cut
