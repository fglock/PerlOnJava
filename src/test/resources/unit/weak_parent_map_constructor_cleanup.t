use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr weaken);

# Regression for issue #1224. PPIx::Regexp::Structure constructs an object by
# separating bracket elements from @args, forwarding the remaining children to
# SUPER::__new(), and then installing the bracket arrays. Every element has a
# weak parent-map entry that its DESTROY method removes.
{
    package Issue1224::Element;
    my %parent;

    sub new { bless {}, shift }

    sub _parent {
        my ($self, @arg) = @_;
        my $address = Scalar::Util::refaddr($self);
        if (@arg) {
            my $value = shift @arg;
            if (defined $value) {
                Scalar::Util::weaken($parent{$address} = $value);
            } else {
                delete $parent{$address};
            }
        }
        return $parent{$address};
    }

    sub parent_count { scalar keys %parent }
    sub __PPIX_LEXER__record_capture_number { $_[1] }

    sub DESTROY {
        $_[0]->_parent(undef);
    }
}

{
    package Issue1224::Token;
    our @ISA = 'Issue1224::Element';

    # Match PPIx::Regexp::Token::__new(): its constructor receives a content
    # value followed by an optional named-argument hash.
    sub __new {
        my ($class, $content, %arg) = @_;
        my $self = { content => $content };
        bless $self, ref $class || $class;
        return $self;
    }

    sub significant { 1 }
    sub __PPIX_LEXER__finalize { 0 }
}

{
    package Issue1224::Node;
    our @ISA = 'Issue1224::Element';

    sub __new {
        my ($class, @children) = @_;
        my $self = bless { children => \@children }, ref $class || $class;
        $_->_parent($self) for @children;
        return $self;
    }

    sub elements { @{ $_[0]{children} } }
    sub children { @{ $_[0]{children} } }
    sub __PPIX_LEXER__finalize {
        my ($self, $lexer) = @_;
        my $failures = 0;
        $failures += $_->__PPIX_LEXER__finalize($lexer) for $self->elements;
        return $failures;
    }

    sub __PPIX_LEXER__record_capture_number {
        my ($self, $number) = @_;
        $number = $_->__PPIX_LEXER__record_capture_number($number)
            for $self->children;
        return $number;
    }
}

{
    package Issue1224::Structure;
    our @ISA = 'Issue1224::Node';

    sub __new {
        my ($class, @args) = @_;
        my %bracket;
        $bracket{finish} = [@args ? pop @args : ()];
        $bracket{start} = [@args ? shift @args : ()];
        $bracket{type} = [];

        # PPIx passes both temporary aggregates to a helper before forwarding
        # the remaining arguments to Node::__new().
        $class->_check_for_interpolated_match(\%bracket, \@args);

        my $self = $class->SUPER::__new(@args);
        for my $key (qw(start type finish)) {
            $self->{$key} = [];
            for my $value (@{ $bracket{$key} }) {
                next if !defined $value;
                push @{ $self->{$key} }, $value;
                $value->_parent($self);
            }
        }
        return $self;
    }

    sub _check_for_interpolated_match {
        my (undef, $bracket, $args) = @_;
        return;
    }

    sub elements {
        my ($self) = @_;
        return (
            @{ $self->{start} },
            @{ $self->{type} },
            @{ $self->{children} },
            @{ $self->{finish} },
        );
    }

    sub __PPIX_LEXER__finalize {
        my ($self, $lexer) = @_;
        my $failures = 0;
        $failures += $_->__PPIX_LEXER__finalize($lexer) for $self->elements;
        $self->{max_capture_number} =
            $self->__PPIX_LEXER__record_capture_number(1) - 1;
        return $failures;
    }
}

{
    package Issue1224::Lexer;

    # Match PPIx::Regexp::Lexer::lex() and _make_node(): a lexer-owned nested
    # token vector is popped, its sentinel removed, a closing token appended,
    # and the result forwarded into Structure::__new().
    sub lex {
        my ($self) = @_;
        my @content;
        my $prefix = Issue1224::Token->__new('');
        my $start = Issue1224::Token->__new('/');
        $self->{_rslt} = [[
            '', $start,
            Issue1224::Token->__new('f'),
            Issue1224::Token->__new('o'),
            Issue1224::Token->__new('o'),
        ]];
        my $suffix = Issue1224::Token->__new('');
        my $regexp = $self->_make_node($suffix);
        push @content, $prefix, $regexp, $suffix;
        $self->_finalize(@content);
        return @content;
    }

    sub _make_node {
        my ($self, $token) = @_;
        my @args = @{ pop @{ $self->{_rslt} } };
        shift @args;
        push @args, $token;
        return Issue1224::Structure->__new(@args);
    }

    sub _finalize {
        my ($self, @content) = @_;
        $self->{failures} += $_->__PPIX_LEXER__finalize($self) for @content;
        return;
    }
}

my $lexer = bless {}, 'Issue1224::Lexer';
my @nodes = $lexer->lex;

is(Issue1224::Element->parent_count, 5,
    'constructor registers all bracket and child parent entries');

undef @nodes;
undef $lexer;

is(Issue1224::Element->parent_count, 0,
    'releasing the structure destroys all parent-map children');

done_testing();
