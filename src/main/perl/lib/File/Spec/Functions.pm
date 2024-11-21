package File::Spec::Functions;

use strict;
use warnings;
use Exporter 'import';
use File::Spec;

# Default exported functions
our @EXPORT = qw(
    canonpath
    catdir
    catfile
    curdir
    rootdir
    updir
    no_upwards
    file_name_is_absolute
    path
);

# Functions exported on request
our @EXPORT_OK = qw(
    devnull
    tmpdir
    splitpath
    splitdir
    catpath
    abs2rel
    rel2abs
    case_tolerant
);

# Export all functions with :ALL tag
our %EXPORT_TAGS = (
    ALL => [ @EXPORT, @EXPORT_OK ],
);

sub canonpath {
    return File::Spec->canonpath(@_);
}

sub catdir {
    return File::Spec->catdir(@_);
}

sub catfile {
    return File::Spec->catfile(@_);
}

sub curdir {
    return File::Spec->curdir(@_);
}

sub rootdir {
    return File::Spec->rootdir(@_);
}

sub updir {
    return File::Spec->updir(@_);
}

sub no_upwards {
    return File::Spec->no_upwards(@_);
}

sub file_name_is_absolute {
    return File::Spec->file_name_is_absolute(@_);
}

sub path {
    return File::Spec->path(@_);
}

sub devnull {
    return File::Spec->devnull(@_);
}

sub tmpdir {
    return File::Spec->tmpdir(@_);
}

sub splitpath {
    return File::Spec->splitpath(@_);
}

sub splitdir {
    return File::Spec->splitdir(@_);
}

sub catpath {
    return File::Spec->catpath(@_);
}

sub abs2rel {
    return File::Spec->abs2rel(@_);
}

sub rel2abs {
    return File::Spec->rel2abs(@_);
}

sub case_tolerant {
    return File::Spec->case_tolerant(@_);
}

1;

__END__

=head1 NAME

File::Spec::Functions - portably perform operations on file names

=head1 SYNOPSIS

    use File::Spec::Functions;
    
    $x = catfile('a', 'b');

=head1 DESCRIPTION

This module exports convenience functions for all of the class methods provided by File::Spec.

=head2 Default Exported Functions

=over 4

=item * canonpath

=item * catdir

=item * catfile

=item * curdir

=item * rootdir

=item * updir

=item * no_upwards

=item * file_name_is_absolute

=item * path

=back

=head2 Functions Exported on Request

=over 4

=item * devnull

=item * tmpdir

=item * splitpath

=item * splitdir

=item * catpath

=item * abs2rel

=item * rel2abs

=item * case_tolerant

=back

All the functions may be imported using the C<:ALL> tag.

=cut

