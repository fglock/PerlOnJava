# Documentation at the __END__

package IO::Pty;

use strict;
use warnings;
use Carp;
use IO::Tty qw(TIOCSCTTY TCSETCTTY TIOCNOTTY);
use IO::File;
require POSIX;

our @ISA     = qw(IO::Handle);
our $VERSION = '1.27';
eval { local $^W = 0; local $SIG{__DIE__}; require IO::Stty };
push @ISA, "IO::Stty" if ( not $@ );    # if IO::Stty is installed

sub new {
    my ($class) = $_[0] || "IO::Pty";
    $class = ref($class) if ref($class);
    @_ <= 1 or croak 'usage: new $class';

    my ( $ptyfd, $ttyfd, $ttyname ) = pty_allocate();

    croak "Cannot open a pty" if not defined $ptyfd;

    # Use IO::Handle::new directly to avoid recursion back into IO::Pty::new
    my $pty = IO::Handle::new($class);
    $pty->fdopen($ptyfd, "r+") or croak "Cannot fdopen pty fd $ptyfd: $!";
    $pty->autoflush(1);
    bless $pty => $class;

    my $slave = IO::Handle::new("IO::Tty");
    $slave->fdopen($ttyfd, "r+") or croak "Cannot fdopen slave fd $ttyfd: $!";
    $slave->autoflush(1);
    bless $slave => "IO::Tty";

    ${*$pty}{'io_pty_slave'}     = $slave;
    ${*$pty}{'io_pty_ttyname'}   = $ttyname;
    ${*$slave}{'io_tty_ttyname'} = $ttyname;

    return $pty;
}

sub ttyname {
    @_ == 1 or croak 'usage: $pty->ttyname();';
    my $pty = shift;
    ${*$pty}{'io_pty_ttyname'};
}

sub close_slave {
    @_ == 1 or croak 'usage: $pty->close_slave();';

    my $master = shift;

    if ( exists ${*$master}{'io_pty_slave'} ) {
        close ${*$master}{'io_pty_slave'};
        delete ${*$master}{'io_pty_slave'};
    }
}

sub slave {
    @_ == 1 or croak 'usage: $pty->slave();';

    my $master = shift;

    if ( exists ${*$master}{'io_pty_slave'} ) {
        return ${*$master}{'io_pty_slave'};
    }

    my $tty = ${*$master}{'io_pty_ttyname'};

    my $slave_fd = IO::Tty::_open_tty($tty);
    croak "Cannot open slave $tty: $!" if $slave_fd < 0;

    my $slave = IO::Tty->new_from_fd( $slave_fd, "r+" );
    croak "Cannot create IO::Tty from fd $slave_fd: $!" if not $slave;
    $slave->autoflush(1);

    ${*$slave}{'io_tty_ttyname'}    = $tty;
    ${*$master}{'io_pty_slave'}     = $slave;

    return $slave;
}

sub make_slave_controlling_terminal {
    @_ == 1 or croak 'usage: $pty->make_slave_controlling_terminal();';

    my $self = shift;
    local (*DEVTTY);

    # loose controlling terminal explicitly
    if ( defined TIOCNOTTY ) {
        if ( open( \*DEVTTY, "/dev/tty" ) ) {
            ioctl( \*DEVTTY, TIOCNOTTY, 0 );
            close \*DEVTTY;
        }
    }

    # Create a new 'session', lose controlling terminal.
    if ( POSIX::setsid() == -1 ) {
        warn "setsid() failed, strange behavior may result: $!\r\n" if $^W;
    }

    if ( open( \*DEVTTY, "/dev/tty" ) ) {
        warn "Could not disconnect from controlling terminal?!\n" if $^W;
        close \*DEVTTY;
    }

    # now open slave, this should set it as controlling tty on some systems
    my $ttyname = ${*$self}{'io_pty_ttyname'};
    my $slv     = IO::Tty->new;
    $slv->open( $ttyname, O_RDWR )
      or croak "Cannot open slave $ttyname: $!";

    if ( not exists ${*$self}{'io_pty_slave'} ) {
        ${*$self}{'io_pty_slave'} = $slv;
    }
    else {
        $slv->close;
    }

    # Acquire a controlling terminal if this doesn't happen automatically
    if ( not open( \*DEVTTY, "/dev/tty" ) ) {
        if ( defined TIOCSCTTY ) {
            if ( not defined ioctl( ${*$self}{'io_pty_slave'}, TIOCSCTTY, 0 ) ) {
                warn "warning: TIOCSCTTY failed, slave might not be set as controlling terminal: $!" if $^W;
            }
        }
        elsif ( defined TCSETCTTY ) {
            if ( not defined ioctl( ${*$self}{'io_pty_slave'}, TCSETCTTY, 0 ) ) {
                warn "warning: TCSETCTTY failed, slave might not be set as controlling terminal: $!" if $^W;
            }
        }
        else {
            warn "warning: You have neither TIOCSCTTY nor TCSETCTTY on your system\n" if $^W;
            return 0;
        }
    }

    if ( not open( \*DEVTTY, "/dev/tty" ) ) {
        warn "Error: could not connect pty as controlling terminal!\n";
        return undef;
    }
    else {
        close \*DEVTTY;
    }

    return 1;
}

sub DESTROY {
    my $self = shift;
    delete ${*$self}{'io_pty_slave'};
}

*clone_winsize_from = \&IO::Tty::clone_winsize_from;
*get_winsize        = \&IO::Tty::get_winsize;
*set_winsize        = \&IO::Tty::set_winsize;
*set_raw            = \&IO::Tty::set_raw;

1;

__END__

=head1 NAME

IO::Pty - Pseudo TTY object class

=head1 VERSION

1.27

=head1 DESCRIPTION

PerlOnJava port of IO::Pty. Creates pseudo-terminal pairs.

=head1 AUTHORS

Originally by Graham Barr, based on the Ptty module by Nick Ing-Simmons.
Maintained by Roland Giersig. Ported to PerlOnJava.

=head1 COPYRIGHT

Free software; you can redistribute it and/or modify it under the same
terms as Perl itself.

=cut
