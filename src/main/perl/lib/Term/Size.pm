package Term::Size;

use strict;
use warnings;
use Exporter qw(import);

our $VERSION = '0.211';
our @EXPORT_OK = qw(chars pixels);

sub _size {
    my $fh = @_ ? shift : *STDIN;
    return unless -t $fh;

    my $cols = $ENV{COLUMNS} || 80;
    my $rows = $ENV{LINES}   || 24;
    return ($cols, $rows);
}

sub chars {
    my @size = _size(@_);
    return unless @size;
    return wantarray ? @size : $size[0];
}

sub pixels {
    my @size = _size(@_);
    return unless @size;
    return wantarray ? (0, 0) : 0;
}

1;
