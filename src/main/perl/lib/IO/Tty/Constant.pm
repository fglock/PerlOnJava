package IO::Tty::Constant;

our $VERSION = '1.27';

require Exporter;

our @ISA       = qw(Exporter);
our @EXPORT_OK = qw(TIOCSCTTY TIOCNOTTY TCSETCTTY TIOCGWINSZ TIOCSWINSZ
    O_RDWR O_NOCTTY TCSANOW TCSADRAIN TCSAFLUSH
    ECHO ECHOE ECHOK ECHONL ICANON IEXTEN ISIG NOFLSH TOSTOP
    IGNBRK BRKINT IGNPAR PARMRK INPCK ISTRIP INLCR IGNCR ICRNL
    IXON IXOFF IXANY IMAXBEL
    OPOST CS8 CREAD PARENB HUPCL CLOCAL
    VMIN VTIME);

# Constants are set by IOTty.java via GlobalVariable at XSLoader::load time.
# Each constant is a package variable in this namespace.

# Generate constant subroutines from package variables.
# IOTty.java sets $IO::Tty::Constant::TIOCGWINSZ etc.
# We provide accessor subs so IO::Tty::Constant::TIOCGWINSZ() works.

sub _generate_constant_sub {
    my ($name) = @_;
    no strict 'refs';
    my $full = "IO::Tty::Constant::$name";
    *{$full} = sub () { ${$full} } unless defined &{$full};
}

# Generate subs for all exported constants
for my $name (@EXPORT_OK) {
    _generate_constant_sub($name);
}

1;

__END__

=head1 NAME

IO::Tty::Constant - Terminal Constants for PerlOnJava

=head1 SYNOPSIS

 use IO::Tty::Constant qw(TIOCNOTTY);
 ...

=head1 DESCRIPTION

This module provides terminal-related constants for use with IO::Tty
and IO::Pty on PerlOnJava. Constants are platform-specific and set
at load time by the Java backend.

=cut
