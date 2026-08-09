package HTML::Entities;

use strict;
use warnings;

our $VERSION = '3.83';
our (%entity2char, %char2entity);

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(encode_entities decode_entities _decode_entities);
our @EXPORT_OK = qw(%entity2char %char2entity encode_entities_numeric);

%entity2char = (
    amp => '&', gt => '>', lt => '<', quot => '"', apos => "'",
    nbsp => chr(160), copy => chr(169), reg => chr(174),
);
%char2entity = reverse %entity2char;

sub Version { return $VERSION }

require HTML::Parser;

sub encode_entities {
    my $void = !defined wantarray;
    my $text = $_[0];
    return undef unless defined $text;
    my $unsafe = @_ > 1 && defined $_[1]
        ? $_[1]
        : q{\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\xff<&>'"};
    $text =~ s/([$unsafe])/_encode_char($1)/ge;
    $_[0] = $text if $void;
    return $text;
}

sub encode_entities_numeric {
    my $void = !defined wantarray;
    my $text = $_[0];
    return undef unless defined $text;
    my $unsafe = @_ > 1 && defined $_[1]
        ? $_[1]
        : q{\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\xff<&>'"};
    $text =~ s/([$unsafe])/sprintf('&#x%X;', ord($1))/ge;
    $_[0] = $text if $void;
    return $text;
}

sub _encode_char {
    my ($char) = @_;
    return '&#39;' if $char eq "'";
    return '&' . $char2entity{$char} . ';' if exists $char2entity{$char};
    return sprintf('&#%d;', ord($char));
}

sub encode { goto &encode_entities }
sub decode { goto &decode_entities }
sub encode_numeric { goto &encode_entities_numeric }

1;

__END__

=head1 NAME

HTML::Entities - entity helpers for PerlOnJava's Java-backed HTML parser

=cut
