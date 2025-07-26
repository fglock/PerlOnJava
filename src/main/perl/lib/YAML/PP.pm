package YAML::PP;

use Exporter "import";
use warnings;
use strict;
use XSLoader;

XSLoader::load( 'YAML::PP' );

# NOTE: The core implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/YAMLPP.java

our @EXPORT_OK = qw(Load Dump LoadFile DumpFile);

my $YPP; # cache

sub Load {
    ($YPP ||= __PACKAGE__->new)->load_string(@_);
}

sub Dump {
    ($YPP ||= __PACKAGE__->new)->dump_string(@_);
}

sub LoadFile {
    ($YPP ||= __PACKAGE__->new)->load_file(@_);
}

sub DumpFile {
    ($YPP ||= __PACKAGE__->new)->dump_file(@_);
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
