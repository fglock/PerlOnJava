package threads;

use strict;
use warnings;
our $VERSION = '2.43';
our $threads = 1;

sub all ()      { 0 }
sub running ()  { 1 }
sub joinable () { 2 }

sub create {
    my $caller = caller;
    my $invocant = shift;
    if (ref($invocant)) {
        my $inherited = $invocant->get_stack_size;
        if (ref($_[0]) eq 'HASH') {
            my %options = %{$_[0]};
            $options{stack_size} = $inherited unless exists $options{stack_size};
            $_[0] = \%options;
        }
        else {
            unshift @_, { stack_size => $inherited };
        }
    }
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
sub object { shift; return _object(@_) }
sub list { shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__; return _list(@_) }
sub join { return _join($_[0]) }
sub detach { return _detach(ref($_[0]) ? $_[0] : _self()) }
sub is_running { return _is_running($_[0]) }
sub is_joinable { return _is_joinable($_[0]) }
sub is_detached { return _is_detached(ref($_[0]) ? $_[0] : _self()) }
sub error { return _error($_[0]) }
sub exit { shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__; return _exit(@_) }
sub kill { return _kill(@_) }
sub wantarray { return _wantarray(ref($_[0]) ? $_[0] : _self()) }
sub get_stack_size {
    return ref($_[0]) ? _get_stack_size($_[0]) : _get_stack_size();
}
sub set_stack_size {
    return ref($_[0]) ? _set_stack_size(@_) : _set_stack_size($_[1]);
}
sub set_thread_exit_only { return _set_thread_exit_only(@_) }
sub yield { select undef, undef, undef, 0; return }
sub equal { return defined($_[0]) && defined($_[1]) && $_[0]->tid == $_[1]->tid }
sub _stringify { return 'threads=' . $_[0]->tid }

sub import {
    shift;
    my $caller = caller;
    my @exports = ('async');
    my ($stack_size, $exit_only);

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
            if ($option =~ /^stack/i) {
                $stack_size = $value;
            }
            else {
                $exit_only = $value =~ /^threads?_only$/ ? 1 : 0;
            }
        }
        else {
            require Carp;
            Carp::croak("threads: Unknown import option: $option");
        }
    }

    $stack_size = $ENV{PERL5_ITHREADS_STACK_SIZE}
        if defined $ENV{PERL5_ITHREADS_STACK_SIZE};
    _set_stack_size($stack_size) if defined $stack_size;
    _set_default_exit_only($exit_only) if defined $exit_only;

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

The standard C<stack_size> and C<exit> declarations configure subsequently
created threads. A non-zero stack-size request selects a platform carrier
because Java virtual-thread stack size cannot be selected by the application.
Missing values and unknown import options are errors.

=head1 COMPATIBILITY

The supported lifecycle is C<create>/C<async>, C<self>, C<tid>, C<list>,
C<join>, and C<detach>, together with state and error inspection. Thread
signals, C<object>, C<wantarray>, current-thread detach, creation context, and
the stack-size metadata API are implemented. JVM platform stack sizes are
requests to the JVM rather than portable native-stack guarantees. Shared
storage, locks, and condition variables are provided by L<threads::shared> for
its documented scalar, array, and hash tranche.

C<kill> returns the thread object when a signal is queued to a live, joinable
Java target. It returns undef for a completed, joined, or detached target,
where no signal can be delivered.
