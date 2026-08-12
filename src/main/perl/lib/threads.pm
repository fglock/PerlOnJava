package threads;

use strict;
use warnings;
our $VERSION = '2.27';

sub all ()      { 0 }
sub running ()  { 1 }
sub joinable () { 2 }

sub create {
    my $caller = caller;
    shift;
    my $code_index = ref($_[0]) eq 'HASH' ? 1 : 0;
    if (defined($_[$code_index]) && !ref($_[$code_index])
            && $_[$code_index] !~ /::/) {
        my @args = @_;
        $args[$code_index] = "${caller}::$args[$code_index]";
        return _create(@args);
    }
    return _create(@_);
}

sub new { shift->create(@_) }
sub async (&;@) { return __PACKAGE__->create(@_) }
sub self { return _self() }
sub tid { return ref($_[0]) ? $_[0]->{tid} : _self()->{tid} }
sub list { shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__; return _list(@_) }
sub join { return _join($_[0]) }
sub detach { return _detach($_[0]) }
sub is_running { return _is_running($_[0]) }
sub is_joinable { return _is_joinable($_[0]) }
sub is_detached { return _is_detached($_[0]) }
sub error { return _error($_[0]) }
sub exit { shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__; return _exit(@_) }
sub kill { return }
sub yield { select undef, undef, undef, 0; return }
sub equal { return defined($_[0]) && defined($_[1]) && $_[0]->tid == $_[1]->tid }
sub _stringify { return 'threads=' . $_[0]->tid }

sub import {
    shift;
    my $caller = caller;
    my @exports = ('async');

    while (my $option = shift) {
        if ($option eq 'yield' || $option eq ':all') {
            push @exports, 'yield';
        }
        elsif ($option =~ /^str/i) {
            overload->import('""' => \&tid);
        }
        elsif ($option =~ /^(?:stack|exit)/i) {
            my $value = shift;
            require Carp;
            Carp::croak("threads: Missing argument for option: $option")
                unless defined $value;
            # PerlOnJava owns JVM stack sizing and threads->exit is always
            # thread-only. Accept the standard import spellings so portable
            # programs can declare their intent without changing semantics.
        }
        else {
            require Carp;
            Carp::croak("threads: Unknown import option: $option");
        }
    }

    no strict 'refs';
    *{"${caller}::$_"} = \&{$_} for @exports;
}

use overload
    '==' => 'equal',
    '!=' => sub { !equal(@_) },
    '""' => '_stringify',
    fallback => 1;

1;

__END__

=head1 NAME

threads - PerlOnJava interpreter threads

=head1 IMPORT OPTIONS

C<async> is exported by default. C<yield> and C<:all> also export C<yield>.
C<stringify> changes string conversion of a thread object from the stable
C<threads=ID> form to its numeric thread ID.

The standard C<stack_size> and C<exit> declarations are accepted for source
compatibility. JVM stack sizing is runtime-managed, and C<threads-E<gt>exit>
always exits only the calling child ithread. Missing values and unknown import
options are errors.

=head1 COMPATIBILITY

The supported lifecycle is C<create>/C<async>, C<self>, C<tid>, C<list>,
C<join>, and C<detach>, together with state and error inspection. Thread
signals (C<kill>), per-thread stack sizing, C<object>, and C<wantarray> are not
yet implemented. Shared storage, locks, and condition variables are provided
by L<threads::shared> for its documented scalar, array, and hash tranche.
