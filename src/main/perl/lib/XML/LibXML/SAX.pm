# $Id$
#
# This is free software, you may use it and distribute it under the same terms as
# Perl itself.
#
# Copyright 2001-2003 AxKit.com Ltd., 2002-2006 Christian Glahn, 2006-2009 Petr Pajas
#
#

package XML::LibXML::SAX;

use strict;
use warnings;

use vars qw($VERSION @ISA);

$VERSION = "2.0210"; # VERSION TEMPLATE: DO NOT CHANGE

use XML::LibXML;
use XML::SAX::Base;

use parent qw(XML::SAX::Base);

use Carp;
use IO::File;

sub CLONE_SKIP {
  return $XML::LibXML::__threads_shared ? 0 : 1;
}

sub set_feature {
	my ($self, $feat, $val) = @_;

	if ($feat eq 'http://xmlns.perl.org/sax/join-character-data') {
		$self->{JOIN_CHARACTERS} = $val;
		return 1;
	}

	shift(@_);
	return $self->SUPER::set_feature(@_);
}

sub _parse_characterstream {
    my ( $self, $fh ) = @_;
    # this my catch the xml decl, so the parser won't get confused about
    # a possibly wrong encoding.
    croak( "not implemented yet" );
}

# See:
# https://rt.cpan.org/Public/Bug/Display.html?id=132759
sub _calc_new_XML_LibXML_parser_for_compatibility_with_XML_Simple_etc
{
    return XML::LibXML->new( expand_entities => 1, );
}

sub _parse_bytestream {
    my ( $self, $fh ) = @_;
    $self->{ParserOptions}{LibParser}      = $self->_calc_new_XML_LibXML_parser_for_compatibility_with_XML_Simple_etc() unless defined $self->{ParserOptions}{LibParser};
    $self->{ParserOptions}{ParseFunc}      = \&XML::LibXML::parse_fh;
    $self->{ParserOptions}{ParseFuncParam} = $fh;
    $self->_parse;
    return $self->end_document({});
}

sub _parse_string {
    my ( $self, $string ) = @_;
    $self->{ParserOptions}{LibParser}      = $self->_calc_new_XML_LibXML_parser_for_compatibility_with_XML_Simple_etc() unless defined $self->{ParserOptions}{LibParser};
    $self->{ParserOptions}{ParseFunc}      = \&XML::LibXML::parse_string;
    $self->{ParserOptions}{ParseFuncParam} = $string;
    $self->_parse;
    return $self->end_document({});
}

sub _parse_systemid {
    my $self = shift;
    $self->{ParserOptions}{LibParser}      = $self->_calc_new_XML_LibXML_parser_for_compatibility_with_XML_Simple_etc() unless defined $self->{ParserOptions}{LibParser};
    $self->{ParserOptions}{ParseFunc}      = \&XML::LibXML::parse_file;
    $self->{ParserOptions}{ParseFuncParam} = shift;
    $self->_parse;
    return $self->end_document({});
}

sub parse_chunk {
    my ( $self, $chunk ) = @_;
    $self->{ParserOptions}{LibParser}      = $self->_calc_new_XML_LibXML_parser_for_compatibility_with_XML_Simple_etc() unless defined $self->{ParserOptions}{LibParser};
    $self->{ParserOptions}{ParseFunc}      = \&XML::LibXML::parse_xml_chunk;
    $self->{ParserOptions}{LibParser}->{IS_FILTER}=1; # a hack to prevent parse_xml_chunk from issuing end_document
    $self->{ParserOptions}{ParseFuncParam} = $chunk;
    $self->_parse;
    return;
}

sub _parse {
    my $self = shift;
    my $args = bless $self->{ParserOptions}, ref($self);

    if (defined($self->{JOIN_CHARACTERS})) {
    	$args->{LibParser}->{JOIN_CHARACTERS} = $self->{JOIN_CHARACTERS};
    } else {
    	$args->{LibParser}->{JOIN_CHARACTERS} = 0;
    }

    $args->{LibParser}->set_handler( $self );
    my $dom;
    eval {
      $dom = $args->{ParseFunc}->($args->{LibParser}, $args->{ParseFuncParam});
    };
    my $parse_err = $@;

    # Generate SAX events from the DOM tree so that SAX handlers
    # (e.g. XML::LibXML::SAX::Builder) build their result.
    if ( !$parse_err && defined $dom ) {
        eval {
            $self->start_document({});
            _fire_sax_events($self, $dom);
        };
        $parse_err ||= $@;
    }

    # break a possible circular reference
    $args->{LibParser}->set_handler( undef );
    if ( $parse_err ) {
        chomp $parse_err unless ref $parse_err;
        croak $parse_err;
    }
    return;
}

# -----------------------------------------------------------------------
# Walk a DOM node and fire SAX events to $handler.
# Called for both Document and DocumentFragment roots.
# end_document is NOT fired here; callers (_parse_string etc.) do that.
# -----------------------------------------------------------------------
sub _fire_sax_events {
    my ($handler, $node) = @_;
    my $type = $node->nodeType;

    if ($type == XML::LibXML::XML_DOCUMENT_NODE()
     || $type == XML::LibXML::XML_DOCUMENT_FRAG_NODE()) {
        foreach my $child ($node->childNodes) {
            _fire_sax_events($handler, $child);
        }
    }
    elsif ($type == XML::LibXML::XML_ELEMENT_NODE()) {
        _fire_sax_element($handler, $node);
    }
    elsif ($type == XML::LibXML::XML_TEXT_NODE()) {
        $handler->characters({ Data => $node->getData });
    }
    elsif ($type == XML::LibXML::XML_CDATA_SECTION_NODE()) {
        $handler->start_cdata({});
        $handler->characters({ Data => $node->getData });
        $handler->end_cdata({});
    }
    elsif ($type == XML::LibXML::XML_COMMENT_NODE()) {
        $handler->comment({ Data => $node->getData });
    }
    elsif ($type == XML::LibXML::XML_PI_NODE()) {
        $handler->processing_instruction({
            Target => $node->nodeName,
            Data   => $node->getData // '',
        });
    }
    elsif ($type == XML::LibXML::XML_ENTITY_REF_NODE()) {
        foreach my $child ($node->childNodes) {
            _fire_sax_events($handler, $child);
        }
    }
    # Silently ignore other node types (DTD, notation, etc.)
}

sub _fire_sax_element {
    my ($handler, $element) = @_;

    my %attrs;
    for my $attr ($element->attributes) {
        next unless ref $attr;  # skip undef
        my $name = $attr->nodeName;
        $attrs{$name} = {
            Name         => $name,
            Value        => $attr->value // '',
            NamespaceURI => $attr->namespaceURI // '',
            Prefix       => $attr->prefix // '',
            LocalName    => $attr->localname,
        };
    }

    my $el = {
        Name         => $element->nodeName,
        NamespaceURI => $element->namespaceURI // '',
        Prefix       => $element->prefix // '',
        LocalName    => $element->localname,
        Attributes   => \%attrs,
    };

    $handler->start_element($el);
    foreach my $child ($element->childNodes) {
        _fire_sax_events($handler, $child);
    }
    $handler->end_element($el);
}

1;
