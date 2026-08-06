package Future::AsyncAwait;

use v5.14;
use warnings;

our $VERSION = '0.71';

# PerlOnJava implements the syntax in its own frontend. Keep activation in
# %^H so it follows Perl's normal lexical pragma scoping rules.
sub import {
    my $class = shift;
    $^H |= 0x020000;
    $^H{'Future::AsyncAwait/async'} = 1;
    $^H{'Future::AsyncAwait/cancel'} = 1
            if grep { $_ eq ':experimental(cancel)' } @_;
}

sub unimport {
    delete $^H{'Future::AsyncAwait/async'};
    delete $^H{'Future::AsyncAwait/cancel'};
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
