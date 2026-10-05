use strict;
use warnings;
use Scalar::Util qw(isweak);
use Test::More;

our @WEAK_NODES;
our $DESTROYED = 0;

my $callback = sub { 'callback remains callable' };
my $callback_alias = $callback;
undef $callback;
is($callback_alias->(), 'callback remains callable',
    'undef of one coderef scalar does not undefine aliases to its CV');

{
    package Issue1649Node;

    sub new {
        my ($class, $tag) = @_;
        my $node = bless { _tag => $tag }, $class;
        push @main::WEAK_NODES, $node;
        Scalar::Util::weaken($main::WEAK_NODES[-1]);
        return $node;
    }

    sub DESTROY { ++$main::DESTROYED }
}

# Keep this dependency-free reduction of HTML::Element::new_from_lol's
# recursive closure and scratch $node pad.  The weak parent links are the
# externally observed ownership boundary from HTML::Tree's refloop test.
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

my $tree = new_from_lol(Issue1649Node => [
    'html', ['head', ['title', 'title text']], ['body', ['p', 'paragraph text']],
]);
my ($body) = grep { defined($_) && $_->{_tag} eq 'body' } @WEAK_NODES;

is(scalar(grep { defined $_ } @WEAK_NODES), 5,
    'recursive builder creates five strongly linked nodes');
ok(isweak($body->{_parent}), 'child parent links are weak');

$tree = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 2,
    'dropping the root releases non-escaped subtrees');
$body = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 0,
    'dropping the escaped subtree releases its remaining nodes');
is($DESTROYED, 5, 'each node is destroyed once');

my $cycle_a = Issue1649Node->new('cycle-a');
my $cycle_b = Issue1649Node->new('cycle-b');
$cycle_a->{next} = $cycle_b;
$cycle_b->{next} = $cycle_a;
undef $cycle_a;
undef $cycle_b;
is(scalar(grep { defined $_ } @WEAK_NODES), 2,
    'strongly linked objects survive after their external roots are released');
my $cycle_survivor = $WEAK_NODES[-1];
Scalar::Util::weaken($cycle_survivor->{next});
$cycle_survivor = undef;
is(scalar(grep { defined $_ } @WEAK_NODES), 0,
    'weakening one cycle edge releases both objects');
is($DESTROYED, 7, 'cycle objects are destroyed after the cycle is broken');

done_testing;
