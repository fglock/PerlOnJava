package IO::Handle;

use strict;
use warnings;
use Carp;
use Symbol;
use 5.38.0;

# Load ungetc()
XSLoader::load( 'IO::Handle' );

our $VERSION = '1.52';

use Exporter 'import';
our @EXPORT = qw();
our @EXPORT_OK = qw(
    autoflush
    output_field_separator
    output_record_separator
    input_record_separator
    format_page_number
    format_lines_per_page
    format_lines_left
    format_name
    format_top_name
    format_line_break_characters
    format_formfeed
    input_line_number
    print
    printf
    getline
    getlines
    _IOFBF
    _IOLBF
    _IONBF
);

# Constants for setvbuf
use constant _IOFBF => 0;  # Fully buffered
use constant _IOLBF => 1;  # Line buffered
use constant _IONBF => 2;  # Unbuffered

# Try to load Java backend if available
my $has_java_backend = 0;
eval {
    require 'org.perlonjava.perlmodule.IOHandleModule';
    IOHandleInit();
    $has_java_backend = 1;
};

# Constructor
sub new {
    my $class = shift;
    my $fh = gensym;
    bless $fh, $class;
}

sub new_from_fd {
    my $class = shift;
    my ($fd, $mode) = @_;

    my $fh = $class->new();
    if ($fh->fdopen($fd, $mode)) {
        return $fh;
    } else {
        # Object is destroyed if fdopen fails
        return undef;
    }
}

# Front-ends for built-in functions

sub close {
    my $fh = shift;
    close($fh);
}

sub eof {
    my $fh = shift;
    eof($fh);
}

sub fcntl {
    my ($fh, $func, $scalar) = @_;
    fcntl($fh, $func, $scalar);
}

sub fileno {
    my $fh = shift;
    fileno($fh);
}

sub format_write {
    my $fh = shift;
    my $fmt = shift;

    my $old_fh = select($fh);
    my $old_fmt = $~ if defined $fmt;
    die "Not implemented: format_write()"
    # $~ = $fmt if defined $fmt;
    # write($fh);
    # $~ = $old_fmt if defined $fmt;
    # select($old_fh);
}

sub getc {
    my $fh = shift;
    CORE::getc($fh);
}

sub ioctl {
    my ($fh, $func, $scalar) = @_;
    ioctl($fh, $func, $scalar);
}

sub read {
    my $fh = shift;
    read($fh, $_[0], $_[1], $_[2] || 0);
}

sub print {
    my $fh = shift;
    print $fh @_;
}

sub printf {
    my $fh = shift;
    printf $fh @_;
}

sub say {
    my $fh = shift;
    say $fh @_;
}

sub stat {
    my $fh = shift;
    stat($fh);
}

sub sysread {
    my $fh = shift;
    sysread($fh, $_[0], $_[1], $_[2] || 0);
}

sub syswrite {
    my $fh = shift;
    syswrite($fh, $_[0], $_[1] || length($_[0]), $_[2] || 0);
}

sub truncate {
    my ($fh, $len) = @_;
    truncate($fh, $len);
}

# Special variable methods

sub autoflush {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $|;
    $| = shift if @_;
    $| = 1 if !defined $| && !@_;  # Default to turning on autoflush
    select($old_fh);
    return $old_val;
}

sub format_page_number {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $%;
    $% = shift if @_;
    select($old_fh);
    return $old_val;
}

sub format_lines_per_page {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $=;
    $= = shift if @_;
    select($old_fh);
    return $old_val;
}

sub format_lines_left {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $-;
    $- = shift if @_;
    select($old_fh);
    return $old_val;
}

sub format_name {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $~;
    $~ = shift if @_;
    select($old_fh);
    return $old_val;
}

sub format_top_name {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $^;
    $^ = shift if @_;
    select($old_fh);
    return $old_val;
}

sub input_line_number {
    my $fh = shift;
    my $old = $.;
    if (@_) {
        # Try to use Java backend if available
        if ($has_java_backend) {
            _set_input_line_number($fh, $_[0]);
        }
        $. = shift;
    }
    return $old;
}

# Class methods for global variables

sub format_line_break_characters {
    shift;  # Ignore class
    my $old = $:;
    $: = shift if @_;
    return $old;
}

sub format_formfeed {
    shift;  # Ignore class
    my $old = $^L;
    $^L = shift if @_;
    return $old;
}

sub output_field_separator {
    shift;  # Ignore class
    my $old = $,;
    $, = shift if @_;
    return $old;
}

sub output_record_separator {
    shift;  # Ignore class
    my $old = $\;
    $\ = shift if @_;
    return $old;
}

sub input_record_separator {
    shift;  # Ignore class
    my $old = $/;
    $/ = shift if @_;
    return $old;
}

# Additional I/O methods

sub fdopen {
    my ($fh, $fd, $mode) = @_;

    if (ref $fd && eval { $fd->isa('IO::Handle') }) {
        # It's an IO::Handle object
        $fd = $fd->fileno();
    } elsif (ref $fd) {
        # It's likely a glob ref
        $fd = fileno($fd);
    }

    return undef unless defined $fd;

    # Map Perl modes to open modes
    my $open_mode = $mode;
    $open_mode =~ s/^r$/</;
    $open_mode =~ s/^w$/>/;
    $open_mode =~ s/^a$/>>/;
    $open_mode =~ s/^r\+$/+</;
    $open_mode =~ s/^w\+$/+>/;

    # Close current handle if open
    close($fh);

    # Duplicate the file descriptor
    if (open($fh, "$open_mode&=", $fd)) {
        return $fh;
    }
    return undef;
}

sub opened {
    my $fh = shift;
    defined fileno($fh);
}

sub getline {
    my $fh = shift;
    scalar <$fh>;
}

sub getlines {
    my $fh = shift;
    wantarray or croak "IO::Handle::getlines called in scalar context";
    <$fh>;
}

sub gets {
    my $fh = shift;
    scalar <$fh>;
}

sub _open_mode_string {
    my ($mode) = @_;
    $mode =~ /^\+?(<|>>?)$/
      or $mode =~ s/^r(\+?)$/$1</
      or $mode =~ s/^w(\+?)$/$1>/
      or $mode =~ s/^a(\+?)$/$1>>/
      or croak "IO::Handle: bad open mode: $mode";
    $mode;
}

sub write {
    my $fh = shift;
    my $buf = shift;
    my $len = shift || length($buf);
    my $offset = shift || 0;

    my $data = substr($buf, $offset, $len);
    print $fh $data;
}

sub error {
    my $fh = shift;
    if ($has_java_backend) {
        return _error($fh);
    }
    # Fallback: check if $! is set
    return $! ? 1 : 0;
}

sub clearerr {
    my $fh = shift;
    return -1 unless defined fileno($fh);

    if ($has_java_backend) {
        return _clearerr($fh);
    }

    # Clear $!
    $! = 0;
    return 0;
}

sub sync {
    my $fh = shift;
    return undef unless defined fileno($fh);

    if ($has_java_backend) {
        return _sync($fh);
    }

    # Fallback: sync not available
    $! = "Function not implemented";
    return undef;
}

sub flush {
    my $fh = shift;

    # First check if handle is valid
    return undef unless defined fileno($fh);

    # Save and restore selected handle
    my $old_fh = select($fh);
    my $old_val = $|;
    $| = 1;  # Turn on autoflush to force flush
    print $fh "";  # Null print to trigger flush
    $| = $old_val;  # Restore
    select($old_fh);

    return "0 but true";
}

sub printflush {
    my $fh = shift;
    my $old_fh = select($fh);
    my $old_val = $|;
    $| = 1;
    my $ret = print $fh @_;
    $| = $old_val;
    select($old_fh);
    return $ret;
}

sub blocking {
    my $fh = shift;

    return undef unless defined fileno($fh);

    if ($has_java_backend) {
        return _blocking($fh, @_);
    }

    # Fallback: blocking mode control not available
    if (@_) {
        $! = "Function not implemented";
        return undef;
    }

    # Assume blocking mode by default
    return 1;
}

sub binmode {
    my $fh = shift;
    if (@_) {
        binmode($fh, $_[0]);
    } else {
        binmode($fh);
    }
}

# Buffer control methods (not available by default on modern Perls)

sub setbuf {
    my ($fh, $buffer) = @_;

    if ($has_java_backend) {
        return _setbuf($fh, $buffer);
    }

    # Not available in pure Perl
    $! = "Function not implemented";
    return undef;
}

sub setvbuf {
    my ($fh, $buffer, $type, $size) = @_;

    if ($has_java_backend) {
        return _setvbuf($fh, $buffer, $type, $size);
    }

    # Not available in pure Perl
    $! = "Function not implemented";
    return undef;
}

# Taint handling

sub untaint {
    my $fh = shift;

    if ($has_java_backend) {
        return _untaint($fh);
    }

    # Can't implement in pure Perl
    return -1;
}

# DESTROY method - called when handle is being destroyed
# In PerlOnJava, this is called by JVM garbage collector
sub DESTROY {
    # Empty DESTROY is fine - the actual cleanup happens in Java
    # This just needs to exist so FileHandle can import it
}

1;

__END__

=head1 NAME

IO::Handle - supply object methods for I/O handles

=head1 SYNOPSIS

    use IO::Handle;

    my $io = IO::Handle->new();
    if ($io->fdopen(fileno(STDIN),"r")) {
        print $io->getline;
        $io->close;
    }

    my $io = IO::Handle->new();
    if ($io->fdopen(fileno(STDOUT),"w")) {
        $io->print("Some text\n");
    }

    # setvbuf is not available by default on Perls 5.8.0 and later.
    use IO::Handle '_IOLBF';
    $io->setvbuf(my $buffer_var, _IOLBF, 1024);

    undef $io;       # automatically closes the file if it's open

    autoflush STDOUT 1;

=head1 DESCRIPTION

IO::Handle is the base class for all other IO handle classes. It is not
intended that objects of IO::Handle would be created directly, but instead
IO::Handle is inherited from by several other classes in the IO hierarchy.

If you are reading this documentation, looking for a replacement for the
FileHandle package, then I suggest you read the documentation for IO::File
too.

=cut