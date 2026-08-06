package Lex;

use strict;
use warnings;

our $VERSION = '0.01';

# The CPAN Lex distribution has effectively disappeared, but a number of
# older parser distributions use only this small scanner API.  Keep the
# compatibility layer deliberately boring: rules are tried in declaration
# order and must consume input at the current cursor.
sub new {
    my ($class, @rules) = @_;
    my @compiled;
    while (@rules) {
        my ($name, $pattern) = splice @rules, 0, 2;
        my $convert = (@rules && ref($rules[0]) eq 'CODE') ? shift @rules : undef;
        my $re = ref($pattern) ? $pattern : qr/\A(?:$pattern)/s;
        push @compiled, [$name, $re, $convert];
    }
    return bless { rules => \@compiled, input => '', pos => 0 }, $class;
}

sub from {
    my ($self, $input) = @_;
    $self->{input} = defined($input) ? "$input" : '';
    $self->{pos} = 0;
    return $self;
}

sub eof {
    my ($self) = @_;
    return $self->{pos} >= length($self->{input});
}

sub nextToken {
    my ($self) = @_;
    return Lex::Token->new('', undef) if $self->eof;

    # Lex's original scanner ignored layout between tokens.  The generated
    # Flowchart lexer also filters NEWLINE and TAB tokens, so doing it here
    # avoids requiring a separate whitespace rule in every old lexer table.
    if (substr($self->{input}, $self->{pos}) =~ /\A[ \f\r\n\t]+/) {
        $self->{pos} += length($&);
        return $self->nextToken;
    }
    my $rest = substr($self->{input}, $self->{pos});
    for my $rule (@{$self->{rules}}) {
        my ($name, $re, $convert) = @$rule;
        if ($rest =~ $re && $-[0] == 0) {
            my $value = $&;
            die "Lex rule '$name' matched empty input\n" unless length $value;
            $self->{pos} += length($value);
            # Lex callbacks receive the token name and matched text.
            $value = $convert->($name, $value) if ref($convert) eq 'CODE';
            return Lex::Token->new($name, $value);
        }
    }
    die "Lex: no rule matched at offset $self->{pos}\n";
}

package Lex::Token;

sub new  { bless { name => $_[1], value => $_[2] }, $_[0] }
sub name { $_[0]->{name} }
sub get  { $_[0]->{value} }

1;
