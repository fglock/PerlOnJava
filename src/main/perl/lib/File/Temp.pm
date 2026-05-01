package File::Temp;

#
# Original File::Temp module by Tim Jenness <tjenness@cpan.org>
# Copyright (c) 2025 by Tim Jenness and the UK Particle Physics and
# Astronomy Research Council.
#
# This is free software; you can redistribute it and/or modify it under
# the same terms as the Perl 5 programming language system itself.
#
# PerlOnJava implementation by Flavio S. Glock.
#

use strict;
use warnings;
use Carp;
use Cwd qw(abs_path);  # Load early to avoid CORE::GLOBAL::stat conflicts
use File::Spec;
use File::Path qw(rmtree);
use Fcntl qw(SEEK_SET SEEK_CUR SEEK_END O_RDWR O_CREAT O_EXCL);
use Scalar::Util qw(blessed);

our $VERSION = '0.2311';

use Exporter 'import';
our @EXPORT = qw();
our @EXPORT_OK = qw(tempfile tempdir mkstemp mkstemps mkdtemp mktemp tmpnam tmpfile tempnam unlink0 unlink1 cleanup SEEK_SET SEEK_CUR SEEK_END);
our %EXPORT_TAGS = (
    'POSIX'    => [qw(tmpnam tmpfile)],
    'mktemp'   => [qw(mkstemp mkstemps mkdtemp mktemp)],
    'seekable' => [qw(SEEK_SET SEEK_CUR SEEK_END)],
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
    require 'org.perlonjava.runtime.perlmodule.FileTemp';
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

# Set up overloading at package level
use overload
    '""' => sub { $_[0]->{_filename} || $_[0]->{_dirname} || '' },
    '0+' => sub { Scalar::Util::refaddr($_[0]) },
    '*{}' => sub { $_[0]->{_fh} },
    fallback => 1;

# Constructor for OO interface
sub new {
    my $class = shift;

    # Handle odd arg count: first arg is a positional template
    # e.g. File::Temp->new("foo-XXXXXXXX") or File::Temp->new(TEMPLATE => "foo-XXXXXXXX")
    my $leading_template = (scalar(@_) % 2 == 1 ? shift(@_) : undef);
    my %args = @_;

    # Positional template overrides TEMPLATE key
    $args{TEMPLATE} = $leading_template if defined $leading_template && !exists $args{TEMPLATE};

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

sub autoflush {
    my $self = shift;
    my $fh = $self->{_fh};
    return unless defined $fh;
    
    my $old = select($fh);
    if (@_) {
        $| = shift;
    }
    my $value = $|;
    select($old);
    return $value;
}

sub close {
    my $self = shift;
    return CORE::close($self->{_fh}) if defined $self->{_fh};
    return;
}

sub seek {
    my $self = shift;
    return CORE::seek($self->{_fh}, $_[0], $_[1]) if defined $self->{_fh};
    return;
}

sub read {
    my $self = shift;
    return CORE::read($self->{_fh}, $_[0], $_[1], defined $_[2] ? $_[2] : 0);
}

sub binmode {
    my $self = shift;
    return @_ ? CORE::binmode($self->{_fh}, $_[0]) : CORE::binmode($self->{_fh});
}

sub getline {
    my $self = shift;
    my $fh = $self->{_fh};
    return <$fh>;
}

sub getlines {
    my $self = shift;
    my $fh = $self->{_fh};
    return <$fh>;
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
sub flush {
    my $self = shift;
    my $fh = $self->{_fh};
    return 1 unless defined $fh;
    # Select the filehandle and enable autoflush to flush any pending output
    my $old_fh = select($fh);
    my $prev_af = $|;
    $| = 1;
    $| = $prev_af;
    select($old_fh);
    return 1;
}

sub AUTOLOAD {
    my $self = shift;
    my $method = $File::Temp::AUTOLOAD;
    $method =~ s/.*:://;

    return if $method eq 'DESTROY';

    if (exists $self->{_fh} && ref($self->{_fh}) && UNIVERSAL::can($self->{_fh}, $method)) {
        return $self->{_fh}->$method(@_);
    }

    # Fallback for IO::Handle methods not directly available on the filehandle
    if ($method eq 'printflush') {
        my $fh = $self->{_fh};
        my $oldfh = select($fh);
        my $old_af = $|;
        $| = 1;
        my $ret = print $fh @_;
        $| = $old_af;
        select($oldfh);
        return $ret;
    }

    croak "Undefined method $method called on File::Temp object";
}

# Main functions

sub tempfile {
    my ($template, %args) = _parse_args(@_);

    # Handle TEMPLATE option (alternative to positional template)
    if (!defined $template && exists $args{TEMPLATE}) {
        $template = delete $args{TEMPLATE};
    }

    # Set defaults
    my $dir = $args{DIR};
    my $suffix = $args{SUFFIX} || '';
    my $unlink = exists $args{UNLINK} ? $args{UNLINK} : (defined wantarray ? 1 : 0);
    my $open   = exists $args{OPEN} ? $args{OPEN} : 1;
    my $perms  = $args{PERMS};  # Custom permissions

    # If no directory specified, use temp directory by default
    # but only when no template with a path was given.
    # In Perl 5, TMPDIR => 1 forces tmpdir; otherwise the template's
    # own directory (if any) is used as-is.
    if (!defined $dir) {
        if (exists $args{TMPDIR} && $args{TMPDIR}) {
            $dir = File::Spec->tmpdir;
        } elsif (!defined $template || $template eq '') {
            $dir = File::Spec->tmpdir;
        }
    }

    # Generate template if not provided
    if (!defined $template || $template eq '') {
        $template = _generate_template();
    }

    # Ensure template has enough X's
    my $x_count = ($template =~ tr/X/X/);
    if ($x_count < 4) {
        croak "Template must end with at least 4 'X' characters";
    }

    # Prepend directory if specified and template doesn't already have one
    if (defined $dir) {
        my ($vol, $dirs, $file_part) = File::Spec->splitpath($template);
        if ($dirs eq '' && $vol eq '') {
            $template = File::Spec->catfile($dir, $template);
        }
    }

    # Create temp file
    my ($fh, $path);
    my $from_java = 0;
    eval {
        if ($suffix) {
            (my $fd, $path) = _mkstemps($template, $suffix);
            $from_java = 1;
        } else {
            (my $fd, $path) = _mkstemp($template);
            $from_java = 1;
        }
    };
    if ($@ || !$from_java) {
        # Fallback to pure Perl implementation - returns open filehandle
        ($fh, $path) = _mkstemp_perl($template, $suffix);
    }

    return wantarray ? (undef, $path) : $path unless $open;

    # For Java path, we need to reopen (Java closed the fd)
    # For Perl path, we already have the filehandle
    if ($from_java || !defined $fh) {
        open($fh, '+<', $path) or croak "Could not open temp file: $!";
    }
    binmode($fh);

    # Apply custom permissions AFTER we have the filehandle open
    if (defined $perms && -e $path) {
        chmod($perms, $path);
    }

    # Set up cleanup if needed
    if ($unlink) {
        _register_cleanup($path, 'file');
    }

    # Return based on context
    return wantarray ? ($fh, $path) : $fh;
}

sub tempdir {
    my ($template, %args) = _parse_args(@_);

    # Handle TEMPLATE option (alternative to positional template)
    if (!defined $template && exists $args{TEMPLATE}) {
        $template = delete $args{TEMPLATE};
    }

    # Set defaults
    my $dir     = $args{DIR};
    my $tmpdir  = $args{TMPDIR};
    my $cleanup = $args{CLEANUP} || 0;

    # Generate template if not provided
    if (!defined $template || $template eq '') {
        $template = _generate_template();
        $tmpdir = 1 unless defined $dir;
    }

    # Ensure template has enough X's
    my $x_count = ($template =~ tr/X/X/);
    if ($x_count < 4) {
        croak "Template must end with at least 4 'X' characters";
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

    open(my $fh, '+<', $path) or croak "Could not open temp file: $!";
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

    open(my $fh, '+<', $path) or croak "Could not open temp file: $!";
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

# Wrapper for File::Spec->tmpdir for compatibility
sub _wrap_file_spec_tmpdir {
    return File::Spec->tmpdir;
}

sub _replace_XX {
    my $template = shift;
    my $chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_';

    # Only replace trailing X's - match X+ at end of string
    $template =~ s/(X+)$/_rand_chars($chars, length($1))/e;
    return $template;
}

# Generate random characters of specified length
sub _rand_chars {
    my ($chars, $len) = @_;
    my $result = '';
    for (1..$len) {
        $result .= substr($chars, int(rand(length($chars))), 1);
    }
    return $result;
}

# Pure Perl fallback implementations

sub _mkstemp_perl {
    my ($template, $suffix) = @_;
    $suffix ||= '';

    for (my $i = 0; $i < 256; $i++) {
        my $path = _replace_XX($template) . $suffix;
        if (sysopen(my $fh, $path, O_RDWR | O_CREAT | O_EXCL, 0600)) {
            # Return the open filehandle and path
            return ($fh, $path);
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

    # Convert to absolute path - important for cleanup after chdir
    my $abs_path = abs_path($path);
    $abs_path = $path unless defined $abs_path;  # fallback if abs_path fails

    if ($type eq 'file') {
        $CLEANUP_FILES{$pid}{$abs_path} = 1;
        eval {
            _register_temp_file($abs_path);
        };
    } else {
        $CLEANUP_DIRS{$pid}{$abs_path} = 1;
        eval {
            _register_temp_dir($abs_path);
        };
    }
}

sub _cleanup_registered {
    my $pid = $$;

    # Clean up files first
    if (exists $CLEANUP_FILES{$pid}) {
        for my $file (keys %{$CLEANUP_FILES{$pid}}) {
            unlink($file) if -e $file;
        }
        delete $CLEANUP_FILES{$pid};
    }

    # Clean up directories - need to handle case where we're IN a dir to be deleted
    if (exists $CLEANUP_DIRS{$pid}) {
        my $cwd = abs_path(File::Spec->curdir);
        my $cwd_to_remove;
        
        for my $dir (keys %{$CLEANUP_DIRS{$pid}}) {
            if (-d $dir) {
                # Check if we're currently in this directory
                my $abs_dir = abs_path($dir);
                if (defined $abs_dir && defined $cwd && $abs_dir eq $cwd) {
                    # We're in this directory - save it for last
                    $cwd_to_remove = $dir;
                    next;
                }
                # Safe to remove - we're not in it
                rmtree($dir);
            }
        }
        
        # Now handle the directory we're sitting in (if any)
        if (defined $cwd_to_remove && -d $cwd_to_remove) {
            # chdir out of the directory first
            my $updir = File::Spec->updir;
            if (chdir($updir)) {
                rmtree($cwd_to_remove);
            } else {
                warn "Could not chdir to $updir to remove $cwd_to_remove: $!";
            }
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
