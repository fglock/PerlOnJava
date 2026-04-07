package XML::Parser::Expat;

# PerlOnJava shim for XML::Parser::Expat
# Replaces the XS-based Expat.pm with Java SAX backend

use strict;

use XSLoader;
use Carp;

our $VERSION = '2.56';

our ( %Encoding_Table, @Encoding_Path );

use File::Spec ();

%Encoding_Table = ();

# Try to find encoding path, but don't require File::ShareDir
my $_share_dir;
eval { require File::ShareDir; $_share_dir = File::ShareDir::dist_dir('XML-Parser') };

@Encoding_Path = (
    ( defined $_share_dir && -d $_share_dir ? ($_share_dir) : () ),
    grep( -d $_,
        map( File::Spec->catdir( $_, qw(XML Parser Encodings) ), @INC ) ),
    File::Spec->curdir
);

# Load the Java XS implementation
XSLoader::load( 'XML::Parser::Expat', $VERSION );

our %Handler_Setters = (
    Start        => \&SetStartElementHandler,
    End          => \&SetEndElementHandler,
    Char         => \&SetCharacterDataHandler,
    Proc         => \&SetProcessingInstructionHandler,
    Comment      => \&SetCommentHandler,
    CdataStart   => \&SetStartCdataHandler,
    CdataEnd     => \&SetEndCdataHandler,
    Default      => \&SetDefaultHandler,
    Unparsed     => \&SetUnparsedEntityDeclHandler,
    Notation     => \&SetNotationDeclHandler,
    ExternEnt    => \&SetExternalEntityRefHandler,
    ExternEntFin => \&SetExtEntFinishHandler,
    Entity       => \&SetEntityDeclHandler,
    Element      => \&SetElementDeclHandler,
    Attlist      => \&SetAttListDeclHandler,
    Doctype      => \&SetDoctypeHandler,
    DoctypeFin   => \&SetEndDoctypeHandler,
    XMLDecl      => \&SetXMLDeclHandler
);

sub new {
    my ( $class, %args ) = @_;
    my $self = bless \%args, $_[0];
    $args{_State_} = 0;
    $args{Context} = [];
    $args{Namespaces}   ||= 0;
    $args{ErrorMessage} ||= '';
    if ( $args{Namespaces} ) {
        $args{Namespace_Table} = {};
        $args{Namespace_List}  = [undef];
        $args{Prefix_Table}    = {};
        $args{New_Prefixes}    = [];
    }
    $args{_Setters} = \%Handler_Setters;
    $args{Parser}   = ParserCreate(
        $self, $args{ProtocolEncoding},
        $args{Namespaces}
    );

    $self;
}

sub load_encoding {
    my ($file) = @_;
    # Java handles encodings natively, but return a name for compatibility
    $file =~ s!([^/]+)$!\L$1\E!;
    $file =~ s/\.enc$//;
    return $file;
}

sub setHandlers {
    my ( $self, @handler_pairs ) = @_;

    croak("Uneven number of arguments to setHandlers method")
      if ( int(@handler_pairs) & 1 );

    my @ret;

    while (@handler_pairs) {
        my $type    = shift @handler_pairs;
        my $handler = shift @handler_pairs;
        croak "Handler for $type not a Code ref"
          unless ( !defined($handler) or !$handler or ref($handler) eq 'CODE' );

        my $hndl = $self->{_Setters}->{$type};

        unless ( defined($hndl) ) {
            my @types = sort keys %{ $self->{_Setters} };
            croak("Unknown Expat handler type: $type\n Valid types: @types");
        }

        my $old = &$hndl( $self->{Parser}, $handler );
        push( @ret, $type, $old );
    }

    return @ret;
}

sub xpcroak {
    my ( $self, $message ) = @_;

    my $eclines = $self->{ErrorContext};
    my $line    = GetCurrentLineNumber( $_[0]->{Parser} );
    $message .= " at line $line";
    $message .= ":\n" . $self->position_in_context($eclines)
      if defined($eclines);
    croak $message;
}

sub xpcarp {
    my ( $self, $message ) = @_;

    my $eclines = $self->{ErrorContext};
    my $line    = GetCurrentLineNumber( $_[0]->{Parser} );
    $message .= " at line $line";
    $message .= ":\n" . $self->position_in_context($eclines)
      if defined($eclines);
    carp $message;
}

sub default_current {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return DefaultCurrent( $self->{Parser} );
    }
}

sub recognized_string {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return RecognizedString( $self->{Parser} );
    }
}

sub original_string {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return OriginalString( $self->{Parser} );
    }
}

sub current_line {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return GetCurrentLineNumber( $self->{Parser} );
    }
}

sub current_column {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return GetCurrentColumnNumber( $self->{Parser} );
    }
}

sub current_byte {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return GetCurrentByteIndex( $self->{Parser} );
    }
}

sub current_length {
    my $self = shift;
    if ( $self->{_State_} == 1 ) {
        return GetCurrentByteCount( $self->{Parser} );
    }
}

sub base {
    my ( $self, $newbase ) = @_;
    my $p       = $self->{Parser};
    my $oldbase = GetBase($p);
    SetBase( $p, $newbase ) if @_ > 1;
    return $oldbase;
}

sub context {
    my $ctx = $_[0]->{Context};
    @$ctx;
}

sub current_element {
    my ($self) = @_;
    @{ $self->{Context} } ? $self->{Context}->[-1] : undef;
}

sub in_element {
    my ( $self, $element ) = @_;
    @{ $self->{Context} }
      ? $self->eq_name( $self->{Context}->[-1], $element )
      : undef;
}

sub within_element {
    my ( $self, $element ) = @_;
    my $cnt = 0;
    foreach ( @{ $self->{Context} } ) {
        $cnt++ if $self->eq_name( $_, $element );
    }
    return $cnt;
}

sub depth {
    my ($self) = @_;
    int( @{ $self->{Context} } );
}

sub element_index {
    my ($self) = @_;

    if ( $self->{_State_} == 1 ) {
        return ElementIndex( $self->{Parser} );
    }
}

################
# Namespace methods

sub namespace {
    my ( $self, $name ) = @_;
    no warnings 'numeric';
    $self->{Namespace_List}->[ int($name) ];
}

sub eq_name {
    my ( $self, $nm1, $nm2 ) = @_;
    no warnings 'numeric';
    int($nm1) == int($nm2) and $nm1 eq $nm2;
}

sub generate_ns_name {
    my ( $self, $name, $namespace ) = @_;

    $namespace
      ? GenerateNSName(
        $name, $namespace, $self->{Namespace_Table},
        $self->{Namespace_List}
      )
      : $name;
}

sub new_ns_prefixes {
    my ($self) = @_;
    if ( $self->{Namespaces} ) {
        return @{ $self->{New_Prefixes} };
    }
    return ();
}

sub expand_ns_prefix {
    my ( $self, $prefix ) = @_;

    if ( $self->{Namespaces} ) {
        my $stack = $self->{Prefix_Table}->{$prefix};
        return ( defined($stack) and @$stack ) ? $stack->[-1] : undef;
    }

    return undef;
}

sub current_ns_prefixes {
    my ($self) = @_;

    if ( $self->{Namespaces} ) {
        my %set = %{ $self->{Prefix_Table} };

        if ( exists $set{'#default'} and not defined( $set{'#default'}->[-1] ) ) {
            delete $set{'#default'};
        }

        return keys %set;
    }

    return ();
}

################################################################
# Namespace declaration handlers

sub NamespaceStart {
    my ( $self, $prefix, $uri ) = @_;

    $prefix = '#default' unless defined $prefix;
    my $stack = $self->{Prefix_Table}->{$prefix};

    if ( defined $stack ) {
        push( @$stack, $uri );
    }
    else {
        $self->{Prefix_Table}->{$prefix} = [$uri];
    }

    push( @{ $self->{New_Prefixes} }, $prefix );
}

sub NamespaceEnd {
    my ( $self, $prefix ) = @_;

    $prefix = '#default' unless defined $prefix;

    my $stack = $self->{Prefix_Table}->{$prefix};
    if ( @$stack > 1 ) {
        pop(@$stack);
    }
    else {
        delete $self->{Prefix_Table}->{$prefix};
    }
}

################

sub specified_attr {
    my $self = shift;

    if ( $self->{_State_} == 1 ) {
        return GetSpecifiedAttributeCount( $self->{Parser} );
    }
}

sub finish {
    my ($self) = @_;
    if ( $self->{_State_} == 1 ) {
        my $parser = $self->{Parser};
        UnsetAllHandlers($parser);
    }
}

sub position_in_context {
    my ( $self, $lines ) = @_;
    if ( $self->{_State_} == 1 ) {
        my $parser = $self->{Parser};
        my ( $string, $linepos ) = PositionContext( $parser, $lines );

        return '' unless defined($string);

        my $col = GetCurrentColumnNumber($parser);
        my $ptr = ( '=' x ( $col - 1 ) ) . '^' . "\n";
        my $ret;
        my $dosplit = $linepos < length($string);

        $string .= "\n" unless $string =~ /\n$/;

        if ($dosplit) {
            $ret = substr( $string, 0, $linepos ) . $ptr . substr( $string, $linepos );
        }
        else {
            $ret = $string . $ptr;
        }

        return $ret;
    }
}

sub xml_escape {
    my $self = shift;
    my $text = shift;

    $text =~ s/\&/\&amp;/g;
    $text =~ s/</\&lt;/g;
    foreach (@_) {
        croak "xml_escape: '$_' isn't a single character" if length($_) > 1;

        if ( $_ eq '>' ) {
            $text =~ s/>/\&gt;/g;
        }
        elsif ( $_ eq '"' ) {
            $text =~ s/\"/\&quot;/g;
        }
        elsif ( $_ eq "'" ) {
            $text =~ s/\'/\&apos;/g;
        }
        else {
            my $rep = '&#' . sprintf( 'x%X', ord($_) ) . ';';
            if (/\W/) {
                my $ptrn = "\\$_";
                $text =~ s/$ptrn/$rep/g;
            }
            else {
                $text =~ s/$_/$rep/g;
            }
        }
    }
    $text;
}

sub skip_until {
    my $self = shift;
    if ( $self->{_State_} <= 1 ) {
        SkipUntil( $self->{Parser}, $_[0] );
    }
}

################
# Security API stubs

sub billion_laughs_attack_protection_maximum_amplification {
    my ( $self, $factor ) = @_;
    # Stub - Java SAX has its own security model
    SetBillionLaughsAttackProtectionMaximumAmplification( $self->{Parser}, $factor ) if defined $factor;
    return 1;
}

sub billion_laughs_attack_protection_activation_threshold {
    my ( $self, $threshold ) = @_;
    SetBillionLaughsAttackProtectionActivationThreshold( $self->{Parser}, $threshold ) if defined $threshold;
    return 1;
}

sub alloc_tracker_maximum_amplification {
    my ( $self, $factor ) = @_;
    SetAllocTrackerMaximumAmplification( $self->{Parser}, $factor ) if defined $factor;
    return 1;
}

sub alloc_tracker_activation_threshold {
    my ( $self, $threshold ) = @_;
    SetAllocTrackerActivationThreshold( $self->{Parser}, $threshold ) if defined $threshold;
    return 1;
}

sub reparse_deferral_enabled {
    my ( $self, $enabled ) = @_;
    SetReparseDeferralEnabled( $self->{Parser}, $enabled ) if defined $enabled;
    return 1;
}

################
# Expat library version info

sub expat_version {
    return ExpatVersion();
}

sub expat_version_info {
    my %info = ExpatVersionInfo();
    return %info;
}

################

sub release {
    my $self = shift;
    ParserRelease( $self->{Parser} );
}

sub DESTROY {
    my $self = shift;
    ParserFree( $self->{Parser} ) if $self->{Parser};
}

sub parse {
    my $self = shift;
    my $arg  = shift;
    croak 'Parse already in progress (Expat)' if $self->{_State_};
    $self->{_State_} = 1;
    my $parser = $self->{Parser};
    my $ioref;
    my $result = 0;

    if ( defined $arg ) {
        local *@;
        if ( ref($arg) and UNIVERSAL::isa( $arg, 'IO::Handle' ) ) {
            $ioref = $arg;
        }
        else {
            require IO::Handle;
            eval {
                no strict 'refs';
                if ( ref $arg eq 'GLOB' ) {
                    $ioref = *{$arg}{IO};
                }
                elsif ( ref \$arg eq 'GLOB' ) {
                    $ioref = *{$arg}{IO};
                }
                elsif ( $arg =~ /\A[^\W\d]\w*(?:::\w+)*\z/
                    && defined *{$arg} )
                {
                    $ioref = *{$arg}{IO};
                }
            };
            if ( ref($ioref) eq 'FileHandle' ) {
                require FileHandle;
            }
        }
    }

    if ( defined($ioref) ) {
        my $delim = $self->{Stream_Delimiter};
        my $prev_rs;
        my $ioclass = ref $ioref;
        $ioclass = 'IO::Handle' if !length $ioclass;

        $prev_rs = $ioclass->input_record_separator("\n$delim\n")
          if defined($delim);

        eval { $result = ParseStream( $parser, $ioref, $delim ) };

        $ioclass->input_record_separator($prev_rs)
          if defined($delim);
    }
    else {
        eval { $result = ParseString( $parser, $arg ) };
    }

    if ($@) {
        die $@ if ref $@;
        $self->xpcroak($@) if defined $self->{ErrorContext};
        die $@;
    }

    $self->{_State_} = 2;
    $result or croak $self->{ErrorMessage};
}

sub parsestring {
    my $self = shift;
    $self->parse(@_);
}

sub parsefile {
    my $self = shift;
    croak 'Parser has already been used' if $self->{_State_};

    open( my $fh, '<', $_[0] ) or croak "Couldn't open $_[0]:\n$!";
    binmode($fh);
    my $ret = $self->parse($fh);
    close($fh);
    $ret;
}

################################################################
package    #hide from PAUSE
  XML::Parser::ContentModel;
use overload '""' => \&asString, 'eq' => \&thiseq;

sub EMPTY ()  { 1 }
sub ANY ()    { 2 }
sub MIXED ()  { 3 }
sub NAME ()   { 4 }
sub CHOICE () { 5 }
sub SEQ ()    { 6 }

sub isempty {
    return $_[0]->{Type} == EMPTY;
}

sub isany {
    return $_[0]->{Type} == ANY;
}

sub ismixed {
    return $_[0]->{Type} == MIXED;
}

sub isname {
    return $_[0]->{Type} == NAME;
}

sub name {
    return $_[0]->{Tag};
}

sub ischoice {
    return $_[0]->{Type} == CHOICE;
}

sub isseq {
    return $_[0]->{Type} == SEQ;
}

sub quant {
    return $_[0]->{Quant};
}

sub children {
    my $children = $_[0]->{Children};
    if ( defined $children ) {
        return @$children;
    }
    return undef;
}

sub asString {
    my ($self) = @_;
    my $ret;

    if ( $self->{Type} == NAME ) {
        $ret = $self->{Tag};
    }
    elsif ( $self->{Type} == EMPTY ) {
        return 'EMPTY';
    }
    elsif ( $self->{Type} == ANY ) {
        return 'ANY';
    }
    elsif ( $self->{Type} == MIXED ) {
        $ret = '(#PCDATA';
        foreach ( @{ $self->{Children} } ) {
            $ret .= '|' . $_;
        }
        $ret .= ')';
    }
    else {
        my $sep = $self->{Type} == CHOICE ? '|' : ',';
        $ret = '(' . join( $sep, map { $_->asString } @{ $self->{Children} } ) . ')';
    }

    $ret .= $self->{Quant} if $self->{Quant};
    return $ret;
}

sub thiseq {
    my $self = shift;

    return $self->asString eq $_[0];
}

################################################################
package    #hide from PAUSE
  XML::Parser::ExpatNB;

use Carp;

our @ISA = qw(XML::Parser::Expat);

sub parse {
    my $self  = shift;
    my $class = ref($self);
    croak "parse method not supported in $class";
}

sub parsestring {
    my $self  = shift;
    my $class = ref($self);
    croak "parsestring method not supported in $class";
}

sub parsefile {
    my $self  = shift;
    my $class = ref($self);
    croak "parsefile method not supported in $class";
}

sub parse_more {
    my ( $self, $data ) = @_;

    $self->{_State_} = 1;
    my $ret = XML::Parser::Expat::ParsePartial( $self->{Parser}, $data );

    croak $self->{ErrorMessage} unless $ret;
}

sub parse_done {
    my $self = shift;

    my $ret = XML::Parser::Expat::ParseDone( $self->{Parser} );
    unless ($ret) {
        my $msg = $self->{ErrorMessage};
        $self->release;
        croak $msg;
    }

    $self->{_State_} = 2;

    my $result = $ret;
    my @result = ();
    my $final  = $self->{FinalHandler};
    if ( defined $final ) {
        if (wantarray) {
            @result = &$final($self);
        }
        else {
            $result = &$final($self);
        }
    }

    $self->release;

    return unless defined wantarray;
    return wantarray ? @result : $result;
}

################################################################

package    #hide from PAUSE
  XML::Parser::Encinfo;

sub DESTROY {
    # No-op - Java handles encoding cleanup
}

1;
