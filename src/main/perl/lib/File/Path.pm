package File::Path;

use strict;
use warnings;
use Carp;

our $VERSION = '2.18';

use Exporter 'import';
our @EXPORT = qw(mkpath rmtree);
our @EXPORT_OK = qw(make_path remove_tree mkpath rmtree);

sub make_path {
    _make_path_perl(@_);
}

sub remove_tree {
    _remove_tree_perl(@_);
}

sub mkpath {
    _mkpath_perl(@_);
}

sub rmtree {
    _rmtree_perl(@_);
}

# Pure Perl fallbacks (simplified versions)

sub _make_path_perl {
    my @paths;
    my $opts = {};

    if (@_ && ref($_[-1]) eq 'HASH') {
        $opts = pop @_;
    }
    @paths = @_;

    return 0 unless @paths;

    my @created;
    my $mode = $opts->{mode} || $opts->{mask} || 0777;
    my $verbose = $opts->{verbose} || 0;

    for my $path (@paths) {
        next unless defined $path && length $path;
        next if -d $path;

        # Simple mkdir -p implementation
        my @parts = split m{/}, $path;
        my $current = '';

        for my $part (@parts) {
            next unless length $part;
            $current .= '/' . $part;

            next if -d $current;

            if (mkdir($current, $mode)) {
                push @created, $current;
                print "mkdir $current\n" if $verbose;
            } else {
                croak "mkdir $current: $!";
            }
        }
    }

    return wantarray ? @created : scalar(@created);
}

sub _remove_tree_perl {
    my @paths;
    my $opts = {};

    if (@_ && ref($_[-1]) eq 'HASH') {
        $opts = pop @_;
    }
    @paths = @_;

    return 0 unless @paths;

    my $count = 0;
    my $verbose = $opts->{verbose} || 0;

    for my $path (@paths) {
        next unless defined $path && length $path;

        if (-d $path) {
            # Simple recursive removal
            $count += _remove_dir_recursive($path, $verbose);
        } elsif (-f $path) {
            if (unlink($path)) {
                $count++;
                print "unlink $path\n" if $verbose;
            } else {
                croak "unlink $path: $!";
            }
        }
    }

    return $count;
}

sub _remove_dir_recursive {
    my ($dir, $verbose) = @_;
    my $count = 0;

    opendir(my $dh, $dir) or croak "opendir $dir: $!";
    my @entries = grep { $_ ne '.' && $_ ne '..' } readdir($dh);
    closedir($dh);

    for my $entry (@entries) {
        my $path = "$dir/$entry";
        if (-d $path) {
            $count += _remove_dir_recursive($path, $verbose);
        } else {
            if (unlink($path)) {
                $count++;
                print "unlink $path\n" if $verbose;
            }
        }
    }

    if (rmdir($dir)) {
        $count++;
        print "rmdir $dir\n" if $verbose;
    }

    return $count;
}

sub _mkpath_perl {
    my $paths = shift;
    my $verbose = shift || 0;
    my $mode = shift || 0777;

    if (ref($paths) eq 'ARRAY') {
        return _make_path_perl(@$paths, { verbose => $verbose, mode => $mode });
    } else {
        return _make_path_perl($paths, { verbose => $verbose, mode => $mode });
    }
}

sub _rmtree_perl {
    my $paths = shift;
    my $verbose = shift || 0;
    my $safe = shift || 0;

    if (ref($paths) eq 'ARRAY') {
        return _remove_tree_perl(@$paths, { verbose => $verbose, safe => $safe });
    } else {
        return _remove_tree_perl($paths, { verbose => $verbose, safe => $safe });
    }
}

1;

__END__

=head1 NAME

File::Path - Create or remove directory trees

=head1 VERSION

2.18 - released November 4 2020.

=head1 SYNOPSIS

    use File::Path qw(make_path remove_tree);

    @created = make_path('foo/bar/baz', '/zug/zwang');
    @created = make_path('foo/bar/baz', '/zug/zwang', {
        verbose => 1,
        mode => 0711,
    });

    $removed_count = remove_tree('foo/bar/baz', '/zug/zwang', {
        verbose => 1,
        error  => \my $err_list,
        safe => 1,
    });

=head1 DESCRIPTION

This module provides a convenient way to create directories of arbitrary depth
and to delete an entire directory subtree from the filesystem.

=cut