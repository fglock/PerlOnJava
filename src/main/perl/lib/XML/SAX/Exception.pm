package XML::SAX::Exception;

use strict;
use warnings;
use overload '""' => 'as_string', fallback => 1;

our $VERSION = '1.09';

sub new {
    my $class = shift;
    return bless { @_ }, $class;
}

sub throw {
    my $class = shift;
    die $class->new(@_);
}

sub as_string {
    my ($self) = @_;
    return defined($self->{Message}) ? $self->{Message} : ref($self);
}

sub Message      { return $_[0]{Message} }
sub LineNumber   { return $_[0]{LineNumber} }
sub ColumnNumber { return $_[0]{ColumnNumber} }
sub PublicId     { return $_[0]{PublicId} }
sub SystemId     { return $_[0]{SystemId} }

package XML::SAX::Exception::Parse;
our @ISA = qw(XML::SAX::Exception);

package XML::SAX::Exception::NotSupported;
our @ISA = qw(XML::SAX::Exception);

package XML::SAX::Exception::Internal;
our @ISA = qw(XML::SAX::Exception);

1;

__END__

=head1 NAME

XML::SAX::Exception - bundled SAX exception surface used by XML::LibXML

=cut
