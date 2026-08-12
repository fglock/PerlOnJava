package XML::LibXSLT;

use strict;
use warnings;
use Carp ();
sub REQUIRE_XML_LIBXML_ABI_VERSION { 2 }
use XML::LibXML 1.70;
use XSLoader;

our $VERSION = '2.003000';
XSLoader::load('XML::LibXSLT', $VERSION);

sub new {
    my $class = shift;
    return bless { @_ }, $class;
}

sub parse_stylesheet {
    my ($self, $document) = @_;
    return $self->_parse_stylesheet($document);
}

sub parse_stylesheet_file {
    my ($self, $filename) = @_;
    return $self->_parse_stylesheet_file($filename);
}

package XML::LibXSLT::Stylesheet;

sub output_as_chars { shift->_output_string($_[0], 2) }
sub output_as_bytes { shift->_output_string($_[0], 1) }
sub output_string   { shift->_output_string($_[0], 0) }
sub transform_into_chars {
    my $self = shift;
    return $self->output_as_chars($self->transform(@_));
}

1;

__END__

=head1 NAME

XML::LibXSLT - XSLT transformations backed by the JDK JAXP provider

=head1 DESCRIPTION

This PerlOnJava port implements the core XML::LibXSLT stylesheet parsing,
transformation, parameter, output, and metadata APIs without native libxslt.

=head1 COPYRIGHT AND LICENSE

The XML::LibXSLT interface is copyright 2001-2009 AxKit.com Ltd. This port is
free software; you may redistribute it under the same terms as Perl itself.

=cut
