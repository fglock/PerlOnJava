# XML::LibXML -- PerlOnJava bundled shim
# Backed by org.perlonjava.runtime.perlmodule.XMLLibXML (JDK DOM/XPath/SAX).
#
# Copyright 2001-2003 AxKit.com Ltd., 2002-2006 Christian Glahn, 2006-2009 Petr Pajas
# (original licence: same terms as Perl itself)
#
# PerlOnJava port: Java XS backend replaces libxml2/XS backend.

package XML::LibXML;

use strict;
use warnings;

use vars qw($VERSION $ABI_VERSION @ISA @EXPORT @EXPORT_OK %EXPORT_TAGS
            $skipDTD $skipXMLDeclaration $setTagCompression
            $MatchCB $ReadCB $OpenCB $CloseCB %PARSER_FLAGS
            $XML_LIBXML_PARSE_DEFAULTS
            );

use Carp;

use constant XML_XMLNS_NS => 'http://www.w3.org/2000/xmlns/';
use constant XML_XML_NS   => 'http://www.w3.org/XML/1998/namespace';

BEGIN {
    $VERSION     = "2.0210";
    $ABI_VERSION = 2;
    require Exporter;
    use XSLoader ();
    @ISA = qw(Exporter);

    %EXPORT_TAGS = (
        all => [qw(
            XML_ELEMENT_NODE XML_ATTRIBUTE_NODE XML_TEXT_NODE
            XML_CDATA_SECTION_NODE XML_ENTITY_REF_NODE XML_ENTITY_NODE
            XML_PI_NODE XML_COMMENT_NODE XML_DOCUMENT_NODE
            XML_DOCUMENT_TYPE_NODE XML_DOCUMENT_FRAG_NODE XML_NOTATION_NODE
            XML_HTML_DOCUMENT_NODE XML_DTD_NODE XML_ELEMENT_DECL
            XML_ATTRIBUTE_DECL XML_ENTITY_DECL XML_NAMESPACE_DECL
            XML_XINCLUDE_END XML_XINCLUDE_START
            encodeToUTF8 decodeFromUTF8
            XML_XMLNS_NS XML_XML_NS
        )],
        libxml => [qw(
            XML_ELEMENT_NODE XML_ATTRIBUTE_NODE XML_TEXT_NODE
            XML_CDATA_SECTION_NODE XML_ENTITY_REF_NODE XML_ENTITY_NODE
            XML_PI_NODE XML_COMMENT_NODE XML_DOCUMENT_NODE
            XML_DOCUMENT_TYPE_NODE XML_DOCUMENT_FRAG_NODE XML_NOTATION_NODE
            XML_HTML_DOCUMENT_NODE XML_DTD_NODE XML_ELEMENT_DECL
            XML_ATTRIBUTE_DECL XML_ENTITY_DECL XML_NAMESPACE_DECL
            XML_XINCLUDE_END XML_XINCLUDE_START
        )],
        encoding => [qw(encodeToUTF8 decodeFromUTF8)],
        ns       => [qw(XML_XMLNS_NS XML_XML_NS)],
    );
    @EXPORT_OK = ( @{$EXPORT_TAGS{all}} );
    @EXPORT    = ( @{$EXPORT_TAGS{all}} );

    $skipDTD            = 0;
    $skipXMLDeclaration = 0;
    $setTagCompression  = 0;
    $MatchCB = undef; $ReadCB = undef; $OpenCB = undef; $CloseCB = undef;

    # Load Java XS backend (triggers XMLLibXML.initialize())
    XSLoader::load( 'XML::LibXML', $VERSION );

    # Expose encode/decode in our namespace via Common
    *encodeToUTF8   = \&XML::LibXML::Common::encodeToUTF8;
    *decodeFromUTF8 = \&XML::LibXML::Common::decodeFromUTF8;
} # BEGIN

# Load submodules outside BEGIN to avoid circular-dep issues.
# These are pure Perl and require no XS.
use XML::LibXML::Error;
use XML::LibXML::NodeList;
# XPathContext loaded on demand (it does `use XML::LibXML` itself)

# -----------------------------------------------------------------------
# Node type constants (match libxml2 / org.w3c.dom.Node constants)
# -----------------------------------------------------------------------
use constant XML_ELEMENT_NODE            => 1;
use constant XML_ATTRIBUTE_NODE          => 2;
use constant XML_TEXT_NODE               => 3;
use constant XML_CDATA_SECTION_NODE      => 4;
use constant XML_ENTITY_REF_NODE         => 5;
use constant XML_ENTITY_NODE             => 6;
use constant XML_PI_NODE                 => 7;
use constant XML_COMMENT_NODE            => 8;
use constant XML_DOCUMENT_NODE           => 9;
use constant XML_DOCUMENT_TYPE_NODE      => 10;
use constant XML_DOCUMENT_FRAG_NODE      => 11;
use constant XML_NOTATION_NODE           => 12;
use constant XML_HTML_DOCUMENT_NODE      => 13;
use constant XML_DTD_NODE                => 14;
use constant XML_ELEMENT_DECL            => 15;
use constant XML_ATTRIBUTE_DECL          => 16;
use constant XML_ENTITY_DECL             => 17;
use constant XML_NAMESPACE_DECL          => 18;
use constant XML_XINCLUDE_START          => 19;
use constant XML_XINCLUDE_END            => 20;

# -----------------------------------------------------------------------
# Parser flags (subset of libxml2 xmlParserOption)
# -----------------------------------------------------------------------
use constant {
    XML_PARSE_RECOVER   => 1,
    XML_PARSE_NOENT     => 2,
    XML_PARSE_DTDLOAD   => 4,
    XML_PARSE_DTDATTR   => 8,
    XML_PARSE_DTDVALID  => 16,
    XML_PARSE_NOERROR   => 32,
    XML_PARSE_NOWARNING => 64,
    XML_PARSE_PEDANTIC  => 128,
    XML_PARSE_NOBLANKS  => 256,
    XML_PARSE_SAX1      => 512,
    XML_PARSE_XINCLUDE  => 1024,
    XML_PARSE_NONET     => 2048,
    XML_PARSE_NODICT    => 4096,
    XML_PARSE_NSCLEAN   => 8192,
    XML_PARSE_NOCDATA   => 16384,
    XML_PARSE_NOXINCNODE=> 32768,
    XML_PARSE_COMPACT   => 65536,
    XML_PARSE_OLD10     => 131072,
    XML_PARSE_NOBASEFIX => 262144,
    XML_PARSE_HUGE      => 524288,
    XML_PARSE_OLDSAX    => 1048576,
    HTML_PARSE_RECOVER  => 1,
    HTML_PARSE_NOERROR  => 32,
};

$XML_LIBXML_PARSE_DEFAULTS = XML_PARSE_NODICT;

%PARSER_FLAGS = (
    recover             => XML_PARSE_RECOVER,
    expand_entities     => XML_PARSE_NOENT,
    load_ext_dtd        => XML_PARSE_DTDLOAD,
    complete_attributes => XML_PARSE_DTDATTR,
    validation          => XML_PARSE_DTDVALID,
    suppress_errors     => XML_PARSE_NOERROR,
    suppress_warnings   => XML_PARSE_NOWARNING,
    pedantic_parser     => XML_PARSE_PEDANTIC,
    no_blanks           => XML_PARSE_NOBLANKS,
    expand_xinclude     => XML_PARSE_XINCLUDE,
    xinclude            => XML_PARSE_XINCLUDE,
    no_network          => XML_PARSE_NONET,
    clean_namespaces    => XML_PARSE_NSCLEAN,
    no_cdata            => XML_PARSE_NOCDATA,
    no_xinclude_nodes   => XML_PARSE_NOXINCNODE,
    old10               => XML_PARSE_OLD10,
    no_base_fix         => XML_PARSE_NOBASEFIX,
    huge                => XML_PARSE_HUGE,
    oldsax              => XML_PARSE_OLDSAX,
);

my %OUR_FLAGS = (
    recover      => 'XML_LIBXML_RECOVER',
    line_numbers => 'XML_LIBXML_LINENUMBERS',
    URI          => 'XML_LIBXML_BASE_URI',
    base_uri     => 'XML_LIBXML_BASE_URI',
    ext_ent_handler => 'ext_ent_handler',
);

# -----------------------------------------------------------------------
# Version check (compatibility - our "libxml2 version" never changes)
# -----------------------------------------------------------------------
{
    my ($runtime_version) = LIBXML_RUNTIME_VERSION() =~ /^(\d+)/;
    if ( $runtime_version < LIBXML_VERSION() ) {
        warn "Warning: XML::LibXML compiled against libxml2 " . LIBXML_VERSION() .
             ", but runtime libxml2 is older $runtime_version\n";
    }
}

sub VERSION {
    my $class = shift;
    my ($caller) = caller;
    my $req_abi = $ABI_VERSION;
    if (UNIVERSAL::can($caller, 'REQUIRE_XML_LIBXML_ABI_VERSION')) {
        $req_abi = $caller->REQUIRE_XML_LIBXML_ABI_VERSION();
    }
    unless ($req_abi == $ABI_VERSION) {
        my $ver = @_ ? ' ' . $_[0] : '';
        die("This version of $caller requires XML::LibXML$ver (ABI $req_abi), "
          . "which is incompatible with currently installed XML::LibXML "
          . "$VERSION (ABI $ABI_VERSION). Please upgrade $caller, XML::LibXML, or both!");
    }
    return $class->UNIVERSAL::VERSION(@_);
}

sub import {
    my $package = shift;
    __PACKAGE__->export_to_level(1, $package, grep !/^:threads(_shared)?$/, @_);
}

sub threads_shared_enabled { return 0 }
sub CLONE_SKIP { return 1 }

# -----------------------------------------------------------------------
# Parser option helpers
# -----------------------------------------------------------------------

sub _parser_options {
    my ($self, $opts) = @_;
    my $flags;
    if (ref($self)) {
        $flags = ($self->{XML_LIBXML_PARSER_OPTIONS} || 0);
    } else {
        $flags = $XML_LIBXML_PARSE_DEFAULTS;
    }
    my ($key, $value);
    while (($key, $value) = each %$opts) {
        my $f = $PARSER_FLAGS{$key};
        if (defined $f) {
            if ($value) { $flags |= $f } else { $flags &= ~$f }
        } elsif ($key eq 'set_parser_flags') {
            $flags |= $value;
        } elsif ($key eq 'unset_parser_flags') {
            $flags &= ~$value;
        }
    }
    return $flags;
}

sub __parser_option {
    my ($self, $opt) = @_;
    if (@_ > 2) {
        if ($_[2]) { $self->{XML_LIBXML_PARSER_OPTIONS} |= $opt;  return 1 }
        else        { $self->{XML_LIBXML_PARSER_OPTIONS} &= ~$opt; return 0 }
    }
    return ($self->{XML_LIBXML_PARSER_OPTIONS} & $opt) ? 1 : 0;
}

sub option_exists {
    my ($self, $name) = @_;
    return ($PARSER_FLAGS{$name} || $OUR_FLAGS{$name}) ? 1 : 0;
}

sub get_option {
    my ($self, $name) = @_;
    my $flag = $OUR_FLAGS{$name};
    return $self->{$flag} if $flag;
    $flag = $PARSER_FLAGS{$name};
    return $self->__parser_option($flag) if $flag;
    return undef;
}

sub set_option {
    my ($self, $name, $value) = @_;
    my $flag = $OUR_FLAGS{$name};
    return ($self->{$flag} = $value) if $flag;
    $flag = $PARSER_FLAGS{$name};
    return $self->__parser_option($flag, $value) if $flag;
    return undef;
}

sub set_options {
    my $self = shift;
    my $opts = (@_ == 1 && ref($_[0]) eq 'HASH') ? $_[0] : {@_};
    $self->set_option($_ => $opts->{$_}) for keys %$opts;
}

# -----------------------------------------------------------------------
# Parser constructor
# -----------------------------------------------------------------------

my %compatibility_flags = (
    XML_LIBXML_KEEP_BLANKS => 'keep_blanks',
    XML_LIBXML_LINENUMBERS => 'line_numbers',
    XML_LIBXML_BASE_URI    => 'URI',
);

sub new {
    my $class = shift;
    my $self = bless { _State_ => 0 }, $class;
    if (@_) {
        my %opts = ref($_[0]) eq 'HASH' ? %{$_[0]} : @_;
        # compat renames
        for my $old (keys %compatibility_flags) {
            if (exists $opts{$old}) {
                $opts{ $compatibility_flags{$old} } //= delete $opts{$old};
            }
        }
        $opts{no_blanks} = !$opts{keep_blanks}
            if exists($opts{keep_blanks}) && !exists($opts{no_blanks});
        for (keys %OUR_FLAGS) {
            $self->{ $OUR_FLAGS{$_} } = delete $opts{$_} if exists $opts{$_};
        }
        $self->{XML_LIBXML_PARSER_OPTIONS} = $class->_parser_options(\%opts);
    } else {
        $self->{XML_LIBXML_PARSER_OPTIONS} = $XML_LIBXML_PARSE_DEFAULTS;
    }
    return $self;
}

sub _clone {
    my ($self) = @_;
    my $new = ref($self)->new({
        recover      => $self->{XML_LIBXML_RECOVER},
        line_numbers => $self->{XML_LIBXML_LINENUMBERS},
        base_uri     => $self->{XML_LIBXML_BASE_URI},
    });
    $new->{XML_LIBXML_PARSER_OPTIONS} = $self->{XML_LIBXML_PARSER_OPTIONS};
    return $new;
}

# -----------------------------------------------------------------------
# Convenience accessor subs
# -----------------------------------------------------------------------

sub keep_blanks {
    my $self = shift;
    my @args;
    if (scalar @_) { @args = ($_[0] ? 0 : 1) }
    return $self->__parser_option(XML_PARSE_NOBLANKS, @args) ? 0 : 1;
}

sub base_uri {
    my $self = shift;
    if (scalar @_) { $self->{XML_LIBXML_BASE_URI} = shift; return $self }
    return $self->{XML_LIBXML_BASE_URI};
}

sub URI {
    my $self = shift;
    if (scalar @_) { $self->{XML_LIBXML_BASE_URI} = shift; return $self }
    return $self->{XML_LIBXML_BASE_URI};
}

sub recover          { my $self = shift; $self->__parser_option(XML_PARSE_RECOVER,    @_) }
sub recover_silently { my $self = shift; $self->__parser_option(XML_PARSE_RECOVER,    @_) }
sub expand_entities  { my $self = shift; $self->__parser_option(XML_PARSE_NOENT,      @_) }
sub load_ext_dtd     { my $self = shift; $self->__parser_option(XML_PARSE_DTDLOAD,    @_) }
sub complete_attributes { my $self = shift; $self->__parser_option(XML_PARSE_DTDATTR, @_) }
sub validation       { my $self = shift; $self->__parser_option(XML_PARSE_DTDVALID,   @_) }
sub suppress_errors  { my $self = shift; $self->__parser_option(XML_PARSE_NOERROR,    @_) }
sub suppress_warnings{ my $self = shift; $self->__parser_option(XML_PARSE_NOWARNING,  @_) }
sub pedantic_parser  { my $self = shift; $self->__parser_option(XML_PARSE_PEDANTIC,   @_) }
sub expand_xinclude  { my $self = shift; $self->__parser_option(XML_PARSE_XINCLUDE,   @_) }
sub no_network       { my $self = shift; $self->__parser_option(XML_PARSE_NONET,      @_) }
sub clean_namespaces { my $self = shift; $self->__parser_option(XML_PARSE_NSCLEAN,    @_) }
sub no_blanks        { my $self = shift; $self->__parser_option(XML_PARSE_NOBLANKS,   @_) }
sub no_cdata         { my $self = shift; $self->__parser_option(XML_PARSE_NOCDATA,    @_) }
sub huge             { my $self = shift; $self->__parser_option(XML_PARSE_HUGE,       @_) }
sub line_numbers {
    my $self = shift;
    $self->{XML_LIBXML_LINENUMBERS} = shift if scalar @_;
    return $self->{XML_LIBXML_LINENUMBERS};
}

sub input_callbacks {
    my ($self, $icbclass) = @_;
    $self->{XML_LIBXML_CALLBACK_STACK} = $icbclass if defined $icbclass;
    return $self->{XML_LIBXML_CALLBACK_STACK};
}

# -----------------------------------------------------------------------
# parse_string / parse_file / parse_fh / load_xml
# These are thin wrappers around the Java XS _parse_* functions.
# -----------------------------------------------------------------------

sub parse_string {
    my $self = shift;
    croak("parse_string is not a class method!") unless ref $self;
    croak("parse already in progress") if $self->{_State_};
    $self->{_State_} = 1;
    my $result = eval { $self->_parse_string(@_) };
    $self->{_State_} = 0;
    if ($@) { my $e = $@; chomp $e unless ref $e; croak $e }
    return $result;
}

sub parse_file {
    my $self = shift;
    croak("parse_file is not a class method!") unless ref $self;
    croak("parse already in progress") if $self->{_State_};
    $self->{_State_} = 1;
    my $result = eval { $self->_parse_file(@_) };
    $self->{_State_} = 0;
    if ($@) { my $e = $@; chomp $e unless ref $e; croak $e }
    return $result;
}

sub parse_fh {
    my $self = shift;
    croak("parse_fh is not a class method!") unless ref $self;
    croak("parse already in progress") if $self->{_State_};
    $self->{_State_} = 1;
    my $result = eval { $self->_parse_fh(@_) };
    $self->{_State_} = 0;
    if ($@) { my $e = $@; chomp $e unless ref $e; croak $e }
    return $result;
}

sub parse_html_string {
    my $self = shift;
    croak("parse_html_string is not a class method!") unless ref $self;
    $self->{_State_} = 1;
    my $result = eval { $self->_parse_html_string(@_) };
    $self->{_State_} = 0;
    if ($@) { my $e = $@; chomp $e unless ref $e; croak $e }
    return $result;
}

sub processXIncludes {
    my ($self, $doc) = @_;
    croak("No document to process!")
        unless ref($doc) && $doc->isa('XML::LibXML::Document');
    return $self->_processXIncludes($doc);
}

sub _processXIncludes {
    # Stub: XInclude processing not yet implemented; return 0 (no includes processed)
    return 0;
}

sub load_xml {
    my $class_or_self = shift;
    my %args = map { ref($_) eq 'HASH' ? (%$_) : $_ } @_;
    my $URI    = delete($args{URI});
    $URI = "$URI" if defined $URI;
    my $parser = ref($class_or_self) ? $class_or_self->_clone() : $class_or_self->new(\%args);
    my $dom;
    if    (defined $args{location}) { $dom = $parser->parse_file("$args{location}") }
    elsif (defined $args{string})   { $dom = $parser->parse_string($args{string}, $URI) }
    elsif (defined $args{IO})       { $dom = $parser->parse_fh($args{IO}, $URI) }
    else  { croak("XML::LibXML->load_xml: specify location, string, or IO") }
    return $dom;
}

sub load_html {
    my $class_or_self = shift;
    my %args = map { ref($_) eq 'HASH' ? (%$_) : $_ } @_;
    my $URI    = delete($args{URI});
    my $parser = ref($class_or_self) ? $class_or_self->_clone() : $class_or_self->new(\%args);
    my $dom;
    if    (defined $args{location}) { $dom = $parser->parse_file("$args{location}") }
    elsif (defined $args{string})   { $dom = $parser->parse_html_string($args{string}, $URI) }
    elsif (defined $args{IO})       { $dom = $parser->parse_fh($args{IO}, $URI) }
    else  { croak("XML::LibXML->load_html: specify location, string, or IO") }
    return $dom;
}

# -----------------------------------------------------------------------
# createDocument  (DOM Level 2 compat)
# -----------------------------------------------------------------------

sub createDocument {
    my $self = shift;
    if (!@_ || $_[0] =~ m/^\d\.\d$/) {
        return XML::LibXML::Document->new(@_);
    } else {
        my $doc = XML::LibXML::Document->new;
        my $el  = $doc->createElementNS(shift, shift);
        $doc->setDocumentElement($el);
        return $doc;
    }
}

# -----------------------------------------------------------------------
# Document::new — create empty document
# -----------------------------------------------------------------------

{
    package XML::LibXML::Document;
    sub new {
        my ($class, $version, $encoding) = @_;
        $version  //= '1.0';
        $encoding //= 'UTF-8';
        require XML::LibXML;
        my $parser = XML::LibXML->new;
        my $xml = qq{<?xml version="$version" encoding="$encoding"?><_root_/>};
        my $doc = $parser->_parse_string($xml);
        # Remove the placeholder root
        my $root = $doc->documentElement;
        $doc->removeChild($root) if $root;
        return $doc;
    }
}

# -----------------------------------------------------------------------
# Node-level findnodes / find / findvalue / exists (Perl wrappers)
# These delegate to the Java _findnodes / _find registered on Node.
# -----------------------------------------------------------------------

# These are intentionally left as fallbacks in XML::LibXML namespace.
# The Java methods registered on XML::LibXML::Node take priority via @ISA.

sub findnodes {
    my ($node, $xpath) = @_;
    my @nodes = $node->_findnodes($xpath);
    if (wantarray) {
        return @nodes;
    } else {
        return XML::LibXML::NodeList->new_from_ref(\@nodes, 1);
    }
}

sub find {
    my ($node, $xpath) = @_;
    my ($type, @params) = $node->_find($xpath, 0);
    return $type ? $type->new(@params) : undef;
}

sub findvalue {
    my ($node, $xpath) = @_;
    my $res = $node->find($xpath);
    return $res ? $res->to_literal->value : undef;
}

sub exists {
    my ($node, $xpath) = @_;
    my (undef, $value) = $node->_find($xpath, 1);
    return $value;
}

# -----------------------------------------------------------------------
# Node overloads (registered here so all subclasses inherit)
# -----------------------------------------------------------------------

{
    package XML::LibXML::Node;
    use overload
        '""'  => sub { $_[0]->toString(0) },
        'bool'=> sub { 1 },
        '0+'  => sub { $_[0]->unique_key },
        '<=>' => sub { $_[0]->unique_key <=> (ref($_[1]) ? $_[1]->unique_key : $_[1]) },
        'cmp' => sub { $_[0]->unique_key <=> (ref($_[1]) ? $_[1]->unique_key : $_[1]) },
        fallback => 1;
}

{
    package XML::LibXML::Document;
    use overload
        '""'  => sub { $_[0]->toString(0) },
        'bool'=> sub { 1 },
        fallback => 1;
}

{
    package XML::LibXML::Element;
    use XML::LibXML::AttributeHash;
    my %tiecache;
    use overload
        '%{}' => sub {
            my $self = shift;
            # Use overload::StrVal to get a stable address-based key
            # without triggering the "" overload
            my $key = overload::StrVal($self);
            if (!exists $tiecache{$key}) {
                tie my %attr, 'XML::LibXML::AttributeHash', $self, weaken => 0;
                $tiecache{$key} = \%attr;
            }
            return $tiecache{$key};
        },
        fallback => 1;
}

# -----------------------------------------------------------------------
# Misc stubs / compatibility
# -----------------------------------------------------------------------

sub load_catalog { }   # no-op
sub set_handler  { }   # no-op for non-SAX use
sub _init_callbacks    { }  # no-op (SAX callback setup)
sub _cleanup_callbacks { }  # no-op

# -----------------------------------------------------------------------
# Push / incremental parser API
# -----------------------------------------------------------------------

sub init_push {
    my $self = shift;
    delete $self->{CONTEXT} if defined $self->{CONTEXT};
    $self->{CONTEXT} = $self->_start_push(0);
}

sub push {
    my $self = shift;
    if ( not defined $self->{CONTEXT} ) {
        $self->init_push();
    }
    foreach ( @_ ) {
        eval { $self->_push( $self->{CONTEXT}, $_ ); };
        if ( $@ ) {
            # Clean up context so next parse_chunk starts fresh
            delete $self->{CONTEXT};
            my $err = $@;
            chomp $err unless ref $err;
            Carp::croak( $err );
        }
    }
}

sub parse_chunk {
    my $self = shift;
    my $chunk     = shift;
    my $terminate = shift;

    if ( not defined $self->{CONTEXT} ) {
        $self->init_push();
    }

    if ( defined $chunk and length $chunk ) {
        eval { $self->_push( $self->{CONTEXT}, $chunk ); };
        if ( $@ ) {
            delete $self->{CONTEXT};
            my $err = $@;
            chomp $err unless ref $err;
            Carp::croak( $err );
        }
    }

    if ( $terminate ) {
        return $self->finish_push();
    }
    return;
}

sub finish_push {
    my $self    = shift;
    my $recover = shift || 0;
    return undef unless defined $self->{CONTEXT};
    my $retval;
    eval { $retval = $self->_end_push( $self->{CONTEXT}, $recover ); };
    my $err = $@;
    delete $self->{CONTEXT};
    if ( $err ) {
        chomp $err unless ref $err;
        Carp::croak( $err );
    }
    return $retval;
}

sub parse_xml_chunk {
    my $self = shift;
    Carp::croak("parse_xml_chunk is not a class method! Create a parser object with XML::LibXML->new first!") unless ref $self;
    unless ( defined $_[0] and length $_[0] ) {
        Carp::croak("Empty String");
    }
    my $result;
    eval { $result = $self->_parse_xml_chunk( @_ ); };
    my $err = $@;
    if ( $err ) {
        chomp $err unless ref $err;
        Carp::croak( $err );
    }
    return $result;
}

sub parse_balanced_chunk {
    my $self = shift;
    my $rv;
    eval { $rv = $self->parse_xml_chunk( @_ ); };
    my $err = $@;
    if ( $err ) {
        chomp $err unless ref $err;
        Carp::croak( $err );
    }
    return $rv;
}

package XML::LibXML::_SAXParser;  # placeholder

package XML::LibXML;

1;

__END__

=head1 NAME

XML::LibXML - Perl Binding for libxml2 (PerlOnJava JDK-backed shim)

=head1 SYNOPSIS

  use XML::LibXML;
  my $parser = XML::LibXML->new();
  my $doc    = $parser->parse_string($xml_string);
  my $root   = $doc->documentElement;
  print $root->nodeName, "\n";

=head1 DESCRIPTION

This is the PerlOnJava bundled implementation of XML::LibXML.
It is backed by the JDK built-in XML stack (DocumentBuilder, org.w3c.dom.*,
javax.xml.xpath.*) rather than by the native libxml2 C library.

Tier A (required for XML::Diff) is fully implemented.  Some advanced
features (XInclude, DTD validation, custom entity loaders, threads) are
stubs or no-ops.

=cut
