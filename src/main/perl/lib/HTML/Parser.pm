package HTML::Parser;

use strict;

our $VERSION = '3.83';

require HTML::Entities;
require XSLoader;
XSLoader::load('HTML::Parser', $VERSION);

sub new {
    my $class = shift;
    my $self = bless {}, $class;
    return $self->init(@_);
}

sub init {
    my $self = shift;
    $self->_alloc_pstate;

    my %arg = @_;
    my $api_version = delete($arg{api_version}) || (@_ ? 3 : 2);
    if ($api_version >= 4) {
        require Carp;
        Carp::croak("API version $api_version not supported by HTML::Parser $VERSION");
    }

    if ($api_version < 3) {
        $self->handler(text    => 'text',    'self,text,is_cdata');
        $self->handler(end     => 'end',     'self,tagname,text');
        $self->handler(process => 'process', 'self,token0,text');
        $self->handler(start   => 'start',   'self,tagname,attr,attrseq,text');
        $self->handler(comment => sub {
            my ($parser, $tokens) = @_;
            $parser->comment($_) for @$tokens;
        }, 'self,tokens');
        $self->handler(declaration => sub {
            my $parser = shift;
            $parser->declaration(substr($_[0], 2, -1));
        }, 'self,text');
    }

    if (my $handlers = delete $arg{handlers}) {
        $handlers = { @$handlers } if ref($handlers) eq 'ARRAY';
        while (my ($event, $callback) = each %$handlers) {
            $self->handler($event => @$callback);
        }
    }

    while (my ($option, $value) = each %arg) {
        if ($option =~ /^(\w+)_h$/) {
            $self->handler($1 => @$value);
        } elsif ($option =~ /^(?:text|start|end|process|declaration|comment)$/) {
            require Carp;
            Carp::croak("Bad constructor option '$option'");
        } else {
            $self->$option($value);
        }
    }
    return $self;
}

sub parse_file {
    my ($self, $file) = @_;
    my $opened;
    if (!ref($file) && ref(\$file) ne 'GLOB') {
        local *HTML_PARSER_FILE;
        open HTML_PARSER_FILE, '<', $file or return undef;
        binmode HTML_PARSER_FILE;
        $file = *HTML_PARSER_FILE;
        $opened = 1;
    }
    my $chunk = '';
    while (read($file, $chunk, 512)) {
        $self->parse($chunk) or last;
    }
    close($file) if $opened;
    return $self->eof;
}

sub netscape_buggy_comment {
    my $self = shift;
    require Carp;
    Carp::carp('netscape_buggy_comment() is deprecated; use strict_comment() instead');
    my $old = !$self->strict_comment;
    $self->strict_comment(!shift) if @_;
    return $old;
}

sub text { }
*start       = \&text;
*end         = \&text;
*comment     = \&text;
*declaration = \&text;
*process     = \&text;

1;

__END__

=head1 NAME

HTML::Parser - Perl loader for PerlOnJava's Java-backed HTML parser

=cut
