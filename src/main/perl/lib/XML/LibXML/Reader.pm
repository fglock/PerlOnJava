package XML::LibXML::Reader;

use strict;
use warnings;
use Carp qw(croak);
use Exporter qw(import);
use XML::LibXML;

our $VERSION = $XML::LibXML::VERSION;

use constant {
    XML_READER_TYPE_NONE                    => 0,
    XML_READER_TYPE_ELEMENT                 => 1,
    XML_READER_TYPE_ATTRIBUTE               => 2,
    XML_READER_TYPE_TEXT                    => 3,
    XML_READER_TYPE_CDATA                   => 4,
    XML_READER_TYPE_ENTITY_REFERENCE        => 5,
    XML_READER_TYPE_ENTITY                  => 6,
    XML_READER_TYPE_PROCESSING_INSTRUCTION  => 7,
    XML_READER_TYPE_COMMENT                 => 8,
    XML_READER_TYPE_DOCUMENT                => 9,
    XML_READER_TYPE_DOCUMENT_TYPE           => 10,
    XML_READER_TYPE_DOCUMENT_FRAGMENT       => 11,
    XML_READER_TYPE_NOTATION                => 12,
    XML_READER_TYPE_WHITESPACE              => 13,
    XML_READER_TYPE_SIGNIFICANT_WHITESPACE  => 14,
    XML_READER_TYPE_END_ELEMENT             => 15,
    XML_READER_TYPE_END_ENTITY              => 16,
    XML_READER_TYPE_XML_DECLARATION         => 17,
};

our @EXPORT = qw(
    XML_READER_TYPE_NONE XML_READER_TYPE_ELEMENT XML_READER_TYPE_ATTRIBUTE
    XML_READER_TYPE_TEXT XML_READER_TYPE_CDATA XML_READER_TYPE_ENTITY_REFERENCE
    XML_READER_TYPE_ENTITY XML_READER_TYPE_PROCESSING_INSTRUCTION
    XML_READER_TYPE_COMMENT XML_READER_TYPE_DOCUMENT XML_READER_TYPE_DOCUMENT_TYPE
    XML_READER_TYPE_DOCUMENT_FRAGMENT XML_READER_TYPE_NOTATION
    XML_READER_TYPE_WHITESPACE XML_READER_TYPE_SIGNIFICANT_WHITESPACE
    XML_READER_TYPE_END_ELEMENT XML_READER_TYPE_END_ENTITY
    XML_READER_TYPE_XML_DECLARATION
);
our @EXPORT_OK = @EXPORT;

sub new {
    my ($class, @arguments) = @_;
    my %args = map { ref($_) eq 'HASH' ? %$_ : $_ } @arguments;
    my $encoding = $args{encoding};
    my $uri = defined $args{URI} ? "$args{URI}" : undef;
    my $options = XML::LibXML->_parser_options(\%args);

    return $class->_newForFile($args{location}, $encoding, $options)
        if defined $args{location};
    return $class->_newForString($args{string}, $uri, $encoding, $options)
        if defined $args{string};
    return $class->_newForIO($args{IO}, $uri, $encoding, $options)
        if defined $args{IO};

    croak 'XML::LibXML::Reader->new: specify location, string, or IO';
}

sub close {
    my ($self) = @_;
    return $self->_close == 0 ? 1 : 0;
}

sub DESTROY {
    my ($self) = @_;
    $self->_DESTROY if $self;
}

1;

__END__

=head1 NAME

XML::LibXML::Reader - forward XML reader backed by PerlOnJava's Java DOM module

=head1 DESCRIPTION

This compatibility layer exposes the core Reader constructors and element
cursor operations implemented by PerlOnJava's Java C<XML::LibXML> module.

=cut
