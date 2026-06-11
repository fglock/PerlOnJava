package File::Remove;

use strict;
use warnings;
use Exporter ();
use File::Path ();

our $VERSION = '1.61';
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(clear remove rm trash);

sub _expand {
    my ($path, $glob) = @_;
    return ($path) unless $glob;
    my @matches = glob $path;
    return @matches ? @matches : ($path);
}

sub remove {
    my @args = @_;
    my $recursive = 0;
    my $glob = 0;

    if (@args && ref($args[0]) eq 'SCALAR') {
        $recursive = ${ shift @args } ? 1 : 0;
    }
    if (@args && ref($args[0]) eq 'HASH') {
        my $opts = shift @args;
        $recursive = $opts->{recursive} ? 1 : $recursive
            if exists $opts->{recursive};
        $recursive = $opts->{recurse} ? 1 : $recursive
            if exists $opts->{recurse};
        $glob = $opts->{glob} ? 1 : 0 if exists $opts->{glob};
    }

    my @removed;
    for my $arg (@args) {
        next unless defined $arg;
        for my $path (_expand($arg, $glob)) {
            next unless defined $path && length $path && -e $path;
            if (-d $path && !-l $path) {
                if ($recursive) {
                    File::Path::rmtree($path);
                    push @removed, $path unless -e $path;
                }
                else {
                    rmdir $path and push @removed, $path;
                }
            }
            else {
                unlink $path and push @removed, $path;
            }
        }
    }

    return wantarray ? @removed : scalar @removed;
}

sub rm { remove(@_) }
sub clear { remove(\1, @_) }
sub trash { remove(@_) }

1;
