package YAML;

use strict;
use warnings;
use YAML::PP;
use Exporter 'import';

our @EXPORT = qw(Load Dump);
our @EXPORT_OK = qw(LoadFile DumpFile);
our $VERSION = '0.01';

my $ypp = YAML::PP->new;

sub Load {
    my ($input) = @_;
    my @docs = $ypp->load_string($input);
    return wantarray ? @docs : $docs[0];
}

sub Dump {
    my @docs = @_;
    return $ypp->dump_string(@docs);
}

sub LoadFile {
    my ($filename) = @_;
    my @docs = $ypp->load_file($filename);
    return wantarray ? @docs : $docs[0];
}

sub DumpFile {
    my ($filename, @docs) = @_;
    return $ypp->dump_file($filename, @docs);
}

1;

