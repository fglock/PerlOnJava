use strict;
use warnings;

# A lazy named sub closes over the same lexical cell initialized by the
# enclosing file body. If the JVM rejects a different lazy body after that
# handoff has been consumed, the whole file is retried by the interpreter;
# both executions must keep using the original closure cell.

package LoopCapture;

my $force;
$force = sub {
    my ($self, $text) = @_;

    require Text::Balanced;
    my $result;
    while (1) {
        my ($prefix, $parenthesized);
        ($parenthesized, $text, $prefix) = do {
            local $@;
            Text::Balanced::extract_bracketed($text, '()', qr/[^\(]*/);
        };

        last unless $parenthesized;

        if ($parenthesized =~ $self->{target_re}) {
            if ($parenthesized =~ /^ \( \s* SELECT \s+ /xi) {
                $parenthesized = "( WRAPPED $parenthesized )";
            }
            else {
                $parenthesized =~ s/^ \( (.+) \) $/$1/x;
                $parenthesized = join ' ', '(', $self->$force($parenthesized), ')';
            }
        }

        $result .= $prefix . $parenthesized;
    }

    return $result . $text;
};

sub transform {
    my ($self, $text) = @_;
    $text = $self->$force($text) if $text =~ $self->{target_re};
    return $text;
}

package main;

print "1..2\n";

my $target = 'items';
my $transformer = bless {
    target_re => qr/ [\s\)] (?: FROM | JOIN ) \s (?: \` \Q$target\E \` | \Q$target\E ) [\s\(] /xi,
}, 'LoopCapture';

my $expected = 'UPDATE items WHERE (  id IN ( WRAPPED ( SELECT id FROM items ) )  )';
my $first = $transformer->transform(
    'UPDATE items WHERE ( id IN ( SELECT id FROM items ) )'
);
print $first eq $expected ? "ok 1\n" : "not ok 1 - first transform: $first\n";

my $second = $transformer->transform(
    'UPDATE items WHERE ( id IN ( SELECT id FROM items ) )'
);
print $second eq $expected ? "ok 2\n" : "not ok 2 - recursive closure survived loop exit: $second\n";
