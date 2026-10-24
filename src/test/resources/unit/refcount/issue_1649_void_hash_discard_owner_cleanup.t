use strict;
use warnings;
use Scalar::Util qw(isweak);
use Test::More;

our @WEAK_NODES;
our $DESTROYED = 0;
our $HOLDER;

{
    package Issue1649VoidHashNode;

    sub new {
        my ($class, $tag) = @_;
        my $node = bless { _tag => $tag }, $class;
        push @main::WEAK_NODES, $node;
        Scalar::Util::weaken($main::WEAK_NODES[-1]);
        return $node;
    }

    sub DESTROY { ++$main::DESTROYED }
}

# Preserve the recursive closure and captured scratch pad from the HTML::Tree
# builder shape while keeping the test independent of that distribution.
sub new_from_lol {
    my $class = shift;
    $class = ref($class) || $class;
    my $lol = $_[0];
    my @ancestor_lols;
    my ($sub, $k, $v, $node);
    $sub = sub {
        my $lol = $_[0];
        return unless @$lol;
        my (@attributes, @children);
        die "cyclic lol" if grep($_ eq $lol, @ancestor_lols);
        push @ancestor_lols, $lol;
        my $tag_name = 'null';
        for (my $i = 0; $i < @$lol; ++$i) {
            if (ref($lol->[$i]) eq 'ARRAY') {
                push @children, $sub->($lol->[$i]);
            } elsif (!ref($lol->[$i])) {
                if ($i == 0) {
                    $tag_name = $lol->[$i];
                } else {
                    push @children, $lol->[$i];
                }
            } elsif (ref($lol->[$i]) eq 'HASH') {
                keys %{ $lol->[$i] };
                while (($k, $v) = each %{ $lol->[$i] }) {
                    push @attributes, $k, $v if defined $v;
                }
            } else {
                die "unsupported node";
            }
        }
        pop @ancestor_lols;
        $node = $class->new($tag_name);
        if (@attributes) {
            my %attributes = @attributes;
            @{$node}{keys %attributes} = values %attributes;
        }
        if (@children) {
            $node->{_content} = \@children;
            foreach my $child (@children) {
                next unless ref $child;
                $child->{_parent} = $node;
                Scalar::Util::weaken($child->{_parent});
            }
        }
        return $node;
    };
    $node = $sub->($lol);
    undef $sub;
    return $node;
}

sub store_body_in_global_hash {
    $HOLDER = { body => $WEAK_NODES[3] };
}

my $tree = new_from_lol(Issue1649VoidHashNode => [
    'html', ['head', ['title', 'title text']], ['body', ['p', 'paragraph text']],
]);
my ($body) = grep { defined($_) && $_->{_tag} eq 'body' } @WEAK_NODES;

store_body_in_global_hash();
$tree = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 2,
    'void-context hash result preserves a global aggregate owner');
$body = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 2,
    'the global hash retains the escaped subtree after the lexical is cleared');
undef $HOLDER;
is(scalar(grep { defined $_ } @WEAK_NODES), 0,
    'releasing the global hash frees the escaped subtree');
is($DESTROYED, 5, 'all nodes are destroyed once');

done_testing;
