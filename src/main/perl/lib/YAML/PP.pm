package YAML::PP;

#
# Original YAML::PP module by TINITA (Tina Mueller)
# A YAML 1.2 processor
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/YAMLPP.java
#

use Exporter "import";
use warnings;
use strict;
use Scalar::Util qw(blessed reftype);
use XSLoader;

XSLoader::load( 'YAML::PP' );

# NOTE: The core implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/YAMLPP.java

our @EXPORT_OK = qw(Load Dump LoadFile DumpFile);

my $YPP; # cache

# Detect a filehandle (GLOB, GLOB ref, IO::Handle-ish object, or CODE that does fileno).
sub _is_filehandle {
    my ($arg) = @_;
    return 0 unless defined $arg;
    return 0 unless ref $arg;
    my $rt = reftype($arg) || '';
    return 1 if $rt eq 'GLOB';
    return 1 if blessed($arg) && $arg->can('getline');
    return 0;
}

sub _slurp_fh {
    my ($fh) = @_;
    local $/;
    return scalar <$fh>;
}

sub Load {
    ($YPP ||= __PACKAGE__->new)->load_string(@_);
}

sub Dump {
    ($YPP ||= __PACKAGE__->new)->dump_string(@_);
}

sub LoadFile {
    my ($file) = @_;
    my $ypp = ($YPP ||= __PACKAGE__->new);
    if (_is_filehandle($file)) {
        return $ypp->load_string(_slurp_fh($file));
    }
    return $ypp->load_file($file);
}

sub DumpFile {
    my ($file, @data) = @_;
    my $ypp = ($YPP ||= __PACKAGE__->new);
    if (_is_filehandle($file)) {
        my $yaml = $ypp->dump_string(@data);
        print { $file } $yaml;
        return 1;
    }
    # Open file ourselves so we can produce YAML::PP-style error message
    open(my $fh, '>', $file) or die "Could not open '$file' for writing: $!\n";
    print { $fh } $ypp->dump_string(@data);
    close $fh;
    return 1;
}

# Method wrappers so $ypp->load_file($fh) / $ypp->dump_file($fh) work too,
# and so load_string in scalar context returns the first document (matches
# the CPAN YAML::PP contract).
{
    my $orig_load_string = \&load_string;
    my $orig_load_file   = \&load_file;
    my $orig_dump_file   = \&dump_file;

    no warnings 'redefine';
    *load_string = sub {
        my ($self, $yaml) = @_;
        my @docs = $orig_load_string->($self, $yaml);
        return wantarray ? @docs : $docs[0];
    };
    *load_file = sub {
        my ($self, $file) = @_;
        if (_is_filehandle($file)) {
            return $self->load_string(_slurp_fh($file));
        }
        my @docs = $orig_load_file->($self, $file);
        return wantarray ? @docs : $docs[0];
    };
    *dump_file = sub {
        my ($self, $file, @data) = @_;
        if (_is_filehandle($file)) {
            my $yaml = $self->dump_string(@data);
            print { $file } $yaml;
            return 1;
        }
        return $orig_dump_file->($self, $file, @data);
    };
}

1;

__END__

=head1 NAME

YAML::PP - YAML 1.2 processor

=head1 SYNOPSIS

    use YAML::PP qw(Load Dump LoadFile DumpFile);

    # OO Interface
    my $ypp = YAML::PP->new(
        schema => 'Core',
        indent => 2,
        header => 1,
        cyclic_refs => 'fatal'
    );

    my $yaml = $ypp->dump_string($data);
    my $data = $ypp->load_string($yaml);

    # Functional Interface
    my $data = Load($yaml);
    my $yaml = Dump($data);

    LoadFile('file.yaml');
    DumpFile('file.yaml', $data);

=head1 DESCRIPTION

YAML::PP is a YAML 1.2 processor that uses SnakeYAML for parsing and emitting YAML.
It supports multiple YAML schemas and provides both OO and functional interfaces.

=cut
