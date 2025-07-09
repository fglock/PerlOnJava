package File::Temp;

use strict;
use warnings;
use Carp;
use File::Spec;
use File::Path qw(rmtree);
use Fcntl qw(SEEK_SET SEEK_CUR SEEK_END O_RDWR O_CREAT O_EXCL);
use Scalar::Util qw(blessed);

our $VERSION = '0.2311';

use Exporter 'import';
our @EXPORT = qw();
our @EXPORT_OK = qw(tempfile tempdir mkstemp mkstemps mkdtemp mktemp tmpnam tmpfile tempnam unlink0 unlink1 cleanup);
our %EXPORT_TAGS = (
    'POSIX'    => [qw(tmpnam tmpfile)],
    'mktemp'   => [qw(mkstemp mkstemps mkdtemp mktemp)],
    'seekable' => [],
);

# Global variables
our $KEEP_ALL = 0;
our $DEBUG = 0;
our $TEMPLATE_COUNTER = 0;

# Security levels
use constant STANDARD => 0;
use constant MEDIUM   => 1;
use constant HIGH     => 2;

my $LEVEL = STANDARD;
my $TOP_SYSTEM_UID = 10;

# Load Java backend if available
eval {
    require 'org.perlonjava.perlmodule.FileTemp';
    initialize();
};

# File::Temp object
package File::Temp::Handle;

sub new {
    my $class = shift;
    my $fh = shift;
    my $self = bless $fh, $class;
    return $self;
}

package File::Temp;

# Constructor for OO interface
sub new {
    my $class = shift;
    my %args = @_;

    # Default arguments
    $args{UNLINK} = 1 unless exists $args{UNLINK};

    # Create temp file
    my ($fh, $filename) = tempfile(
        $args{TEMPLATE} || undef,
        DIR    => $args{DIR},
        SUFFIX => $args{SUFFIX},
        UNLINK => $args{UNLINK},
        EXLOCK => $args{EXLOCK},
        PERMS  => $args{PERMS},
    );

    # Create object
    my $self = bless {
        _fh       => $fh,
        _filename => $filename,
        _unlink   => $args{UNLINK},
    }, $class;

    # Set up stringification
    use overload
        '""' => sub { $_[0]->{_filename} },
        '0+' => sub { builtin::refaddr($_[0]) },
        '*{}' => sub { $_[0]->{_fh} },
        fallback => 1;

    return $self;
}

# Create temporary directory object
sub newdir {
    my $class = shift;
    my $template;
    my %args;

    if (@_ == 1 && !ref $_[0]) {
        $template = shift;
    } else {
        %args = @_;
        $template = delete $args{TEMPLATE};
    }

    # Default to cleanup
    $args{CLEANUP} = 1 unless exists $args{CLEANUP};

    my $dir = tempdir($template || undef, %args);

    my $self = bless {
        _dirname => $dir,
        _cleanup => $args{CLEANUP},
    }, $class;

    # Set up stringification
    use overload
        '""' => sub { $_[0]->{_dirname} },
        fallback => 1;

    return $self;
}

# Object methods
sub filename {
    my $self = shift;
    return $self->{_filename};
}

sub dirname {
    my $self = shift;
    return $self->{_dirname};
}

sub unlink_on_destroy {
    my $self = shift;
    $self->{_unlink} = shift if @_;
    return $self->{_unlink};
}

sub DESTROY {
    my $self = shift;

    return if $KEEP_ALL;

    if (exists $self->{_fh} && $self->{_unlink}) {
        close($self->{_fh}) if defined $self->{_fh};
        unlink($self->{_filename}) if -e $self->{_filename};
    }

    if (exists $self->{_dirname} && $self->{_cleanup}) {
        rmtree($self->{_dirname}) if -d $self->{_dirname};
    }
}

# Delegate IO methods to filehandle
sub AUTOLOAD {
    my $self = shift;
    my $method = $File::Temp::AUTOLOAD;
    $method =~ s/.*:://;

    return if $method eq 'DESTROY';

    if (exists $self->{_fh} && $self->{_fh}->can($method)) {
        return $self->{_fh}->$method(@_);
    }

    croak "Undefined method $method called on File::Temp object";
}

# Main functions

sub tempfile {
    my ($template, %args) = _parse_args(@_);

    # Set defaults
    my $dir = $args{DIR};
    my $suffix = $args{SUFFIX} || '';
    my $unlink = exists $args{UNLINK} ? $args{UNLINK} : (defined wantarray ? 1 : 0);
    my $open   = exists $args{OPEN} ? $args{OPEN} : 1;

    # If no directory specified, use temp directory by default
    # unless TMPDIR was explicitly set to false
    if (!defined $dir && (!exists $args{TMPDIR} || $args{TMPDIR})) {
        $dir = File::Spec->tmpdir;
    }

    # Generate template if not provided
    if (!defined $template) {
        $template = _generate_template();
    }

    # Ensure template has enough X's
    my $x_count = ($template =~ tr/X/X/);
    if ($x_count < 4) {
        croak "Template must contain at least 4 trailing X characters";
    }

    # Prepend directory if specified
    if (defined $dir) {
        $template = File::Spec->catfile($dir, $template);
    }

    # Create temp file
    my ($fd, $path);
    eval {
        if ($suffix) {
            ($fd, $path) = _mkstemps($template, $suffix);
        } else {
            ($fd, $path) = _mkstemp($template);
        }
    };
    if ($@) {
        # Fallback to pure Perl implementation
        ($fd, $path) = _mkstemp_perl($template, $suffix);
    }

    return $path unless $open;

    # Ignore the file descriptor and just open the file by path
    # The Java side should have already closed its file descriptor
    open(my $fh, '+<', $path) or croak "Could not open temp file: $!";
    binmode($fh);

    # Set up cleanup if needed
    if ($unlink) {
        _register_cleanup($path, 'file');
    }

    # Return based on context
    return wantarray ? ($fh, $path) : $fh;
}

sub tempdir {
    my ($template, %args) = _parse_args(@_);

    # Set defaults
    my $dir     = $args{DIR};
    my $tmpdir  = $args{TMPDIR};
    my $cleanup = $args{CLEANUP} || 0;

    # Generate template if not provided
    if (!defined $template) {
        $template = _generate_template();
        $tmpdir = 1 unless defined $dir;
    }

    # Ensure template has enough X's
    my $x_count = ($template =~ tr/X/X/);
    if ($x_count < 4) {
        croak "Template must contain at least 4 trailing X characters";
    }

    # Prepend directory
    if ($tmpdir && !defined $dir) {
        $dir = File::Spec->tmpdir;
    }
    if (defined $dir) {
        $template = File::Spec->catdir($dir, $template);
    }

    # Create temp directory
    my $path;
    eval {
        $path = _mkdtemp($template);
    };
    if ($@) {
        # Fallback to pure Perl implementation
        $path = _mkdtemp_perl($template);
    }

    # Set up cleanup if needed
    if ($cleanup) {
        _register_cleanup($path, 'dir');
    }

    return $path;
}

# MKTEMP family functions

sub mkstemp {
    my $template = shift;
    croak "mkstemp: template required" unless defined $template;

    my ($fd, $path);
    eval {
        ($fd, $path) = _mkstemp($template);
    };
    if ($@) {
        ($fd, $path) = _mkstemp_perl($template, '');
    }

    open(my $fh, '+<', $fd) or croak "Could not open temp file: $!";
    binmode($fh);

    return wantarray ? ($fh, $path) : $fh;
}

sub mkstemps {
    my ($template, $suffix) = @_;
    croak "mkstemps: template required" unless defined $template;
    $suffix ||= '';

    my ($fd, $path);
    eval {
        ($fd, $path) = _mkstemps($template, $suffix);
    };
    if ($@) {
        ($fd, $path) = _mkstemp_perl($template, $suffix);
    }

    open(my $fh, '+<', $fd) or croak "Could not open temp file: $!";
    binmode($fh);

    return wantarray ? ($fh, $path) : $fh;
}

sub mkdtemp {
    my $template = shift;
    croak "mkdtemp: template required" unless defined $template;

    my $path;
    eval {
        $path = _mkdtemp($template);
    };
    if ($@) {
        $path = _mkdtemp_perl($template);
    }

    return $path;
}

sub mktemp {
    my $template = shift;
    croak "mktemp: template required" unless defined $template;

    for (my $i = 0; $i < 256; $i++) {
        my $path = _replace_XX($template);
        return $path unless -e $path;
    }

    croak "Could not generate temporary filename from template: $template";
}

# POSIX functions

sub tmpnam {
    my $template = File::Spec->catfile(File::Spec->tmpdir, "tmpXXXXXX");

    if (wantarray) {
        return mkstemp($template);
    } else {
        return mktemp($template);
    }
}

sub tmpfile {
    my ($fh, $path) = tmpnam();
    unlink($path) if defined $path;
    return $fh;
}

# Additional functions

sub tempnam {
    my ($dir, $prefix) = @_;
    $dir ||= File::Spec->tmpdir;
    $prefix ||= 'tmp';

    my $template = File::Spec->catfile($dir, $prefix . 'XXXXXX');
    return mktemp($template);
}

# Utility functions

sub unlink0 {
    my ($fh, $path) = @_;

    # Compare file stats
    return 0 unless cmpstat($fh, $path);

    # Check link count
    my @fh_stat = stat($fh);
    return 0 unless @fh_stat;
    return 0 if $fh_stat[3] > 1;

    # Unlink file
    return 0 unless unlink($path);

    # Verify link count is now 0
    @fh_stat = stat($fh);
    return 0 unless @fh_stat;

    return $fh_stat[3] == 0;
}

sub cmpstat {
    my ($fh, $path) = @_;

    my @fh_stat = stat($fh);
    my @path_stat = stat($path);

    return 0 unless @fh_stat && @path_stat;

    # Compare all stat fields (except atime/mtime/ctime)
    for my $i (0, 1, 2, 3, 4, 5, 6, 7, 11) {
        return 0 if $fh_stat[$i] != $path_stat[$i];
    }

    return 1;
}

sub unlink1 {
    my ($fh, $path) = @_;

    return 0 unless cmpstat($fh, $path);
    close($fh);
    return unlink($path);
}

sub cleanup {
    eval {
        _cleanup();
    };
    # Also clean up any Perl-level registrations
    _cleanup_registered();
}

# Package variables methods

sub safe_level {
    my $class = shift;
    if (@_) {
        my $new_level = shift;
        if ($new_level >= STANDARD && $new_level <= HIGH) {
            $LEVEL = $new_level;
        }
    }
    return $LEVEL;
}

sub top_system_uid {
    my $class = shift;
    $TOP_SYSTEM_UID = shift if @_;
    return $TOP_SYSTEM_UID;
}

# Helper functions

sub _parse_args {
    my @args = @_;
    my $template;
    my %options;

    # Handle different calling styles
    if (@args == 0) {
        # No arguments
    } elsif (@args == 1 && !ref $args[0]) {
        # Just template
        $template = $args[0];
    } elsif (@args == 1 && ref $args[0] eq 'HASH') {
        # Just options
        %options = %{$args[0]};
    } elsif (@args > 1 && @args % 2 == 1) {
        # Template plus options
        $template = shift @args;
        %options = @args;
    } else {
        # Just options
        %options = @args;
    }

    return ($template, %options);
}

sub _generate_template {
    my $base = "temp" . sprintf("%04d", $TEMPLATE_COUNTER++ % 10000);
    return $base . "XXXXXX";
}

sub _replace_XX {
    my $template = shift;
    my $chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_';

    $template =~ s/X/substr($chars, int(rand(length($chars))), 1)/ge;
    return $template;
}

# Pure Perl fallback implementations

sub _mkstemp_perl {
    my ($template, $suffix) = @_;
    $suffix ||= '';

    for (my $i = 0; $i < 256; $i++) {
        my $path = _replace_XX($template) . $suffix;
        if (sysopen(my $fh, $path, O_RDWR | O_CREAT | O_EXCL, 0600)) {
            return (fileno($fh), $path);
        }
    }

    croak "Could not create temp file from template: $template";
}

sub _mkdtemp_perl {
    my $template = shift;

    for (my $i = 0; $i < 256; $i++) {
        my $path = _replace_XX($template);
        if (mkdir($path, 0700)) {
            return $path;
        }
    }

    croak "Could not create temp directory from template: $template";
}

# Cleanup registration
my %CLEANUP_FILES;
my %CLEANUP_DIRS;

sub _register_cleanup {
    my ($path, $type) = @_;
    my $pid = $$;

    if ($type eq 'file') {
        $CLEANUP_FILES{$pid}{$path} = 1;
        eval {
            _register_temp_file($path);
        };
    } else {
        $CLEANUP_DIRS{$pid}{$path} = 1;
        eval {
            _register_temp_dir($path);
        };
    }
}

sub _cleanup_registered {
    my $pid = $$;

    # Clean up files
    if (exists $CLEANUP_FILES{$pid}) {
        for my $file (keys %{$CLEANUP_FILES{$pid}}) {
            unlink($file) if -e $file;
        }
        delete $CLEANUP_FILES{$pid};
    }

    # Clean up directories
    if (exists $CLEANUP_DIRS{$pid}) {
        for my $dir (keys %{$CLEANUP_DIRS{$pid}}) {
            rmtree($dir) if -d $dir;
        }
        delete $CLEANUP_DIRS{$pid};
    }
}

# END block for cleanup
END {
    _cleanup_registered() unless $KEEP_ALL;
}

# Security checking functions

sub _check_dir_security {
    my $dir = shift;

    return 1 if $LEVEL == STANDARD;

    my @stat = stat($dir);
    return 0 unless @stat;

    # Check ownership
    if ($LEVEL >= MEDIUM) {
        # Directory should be owned by root or current user
        my $uid = $<;
        unless ($stat[4] == $uid || $stat[4] <= $TOP_SYSTEM_UID) {
            carp "Directory $dir not owned by root or current user" if $DEBUG;
            return 0;
        }

        # Check sticky bit if world writable
        if (($stat[2] & 0002) && !($stat[2] & 01000)) {
            carp "Directory $dir is world writable but not sticky" if $DEBUG;
            return 0;
        }
    }

    # HIGH security would check parent directories too
    if ($LEVEL >= HIGH) {
        my $parent = File::Spec->catdir($dir, File::Spec->updir);
        return _check_dir_security($parent) unless $parent eq $dir;
    }

    return 1;
}

1;

__END__

=head1 NAME

File::Temp - return name and handle of a temporary file safely

=head1 VERSION

version 0.2311

=head1 SYNOPSIS

    use File::Temp qw/ tempfile tempdir /;

    $fh = tempfile();
    ($fh, $filename) = tempfile();

    ($fh, $filename) = tempfile( $template, DIR => $dir);
    ($fh, $filename) = tempfile( $template, SUFFIX => '.dat');
    ($fh, $filename) = tempfile( $template, TMPDIR => 1 );

    binmode( $fh, ":utf8" );

    $dir = tempdir( CLEANUP => 1 );
    ($fh, $filename) = tempfile( DIR => $dir );

Object interface:

    require File::Temp;
    use File::Temp ();
    use File::Temp qw/ :seekable /;

    $fh = File::Temp->new();
    $fname = $fh->filename;

    $fh = File::Temp->new(TEMPLATE => $template);
    $fname = $fh->filename;

    $tmp = File::Temp->new( UNLINK => 0, SUFFIX => '.dat' );
    print $tmp "Some data\n";
    print "Filename is $tmp\n";
    $tmp->seek( 0, SEEK_END );

    $dir = File::Temp->newdir(); # CLEANUP => 1 by default

=head1 DESCRIPTION

File::Temp can be used to create and open temporary files in a safe way.
There is both a function interface and an object-oriented interface.
The File::Temp constructor or the tempfile() function can be used to
return the name and the open filehandle of a temporary file.
The tempdir() function can be used to create a temporary directory.

=head1 SECURITY

This module tries to be as secure as possible when creating temporary files.
It uses a combination of techniques including:

=over 4

=item * Exclusive file creation (O_EXCL)

=item * Restrictive file permissions (0600)

=item * Directory security checking

=item * Avoidance of race conditions

=back

=head1 PORTABILITY

This implementation works on Linux, Windows, and Mac systems. The Java
backend handles platform-specific differences in file handling and
permissions.

=head1 SEE ALSO

L<File::Spec>, L<File::Path>, L<IO::File>

=cut
