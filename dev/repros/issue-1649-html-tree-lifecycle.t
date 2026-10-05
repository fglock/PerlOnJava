use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our @weak_elements;
our $have_html_element;

BEGIN {
    no strict 'refs';
    *{'CORE::GLOBAL::bless'} = sub {
        my $reference = shift;
        my $class = @_ ? shift : scalar caller;
        my $object = CORE::bless($reference, $class);
        if ($object->isa('HTML::Element')) {
            push @main::weak_elements, $object;
            Scalar::Util::weaken($main::weak_elements[-1]);
        }
        return $object;
    };

    $have_html_element = eval { require HTML::Element; 1 };
}

plan skip_all => 'HTML::Tree is not installed' unless $have_html_element;

my $tree = HTML::Element->new_from_lol(
    ['html', ['head', ['title']], ['body', ['p']]]
);
my ($body) = $tree->look_down(_tag => 'body');

is(scalar(grep { defined $_ } @weak_elements), 5,
    'new_from_lol creates five strongly linked elements');
ok(isweak($body->{_parent}), 'the body parent link is weak');

$tree = undef;

is(scalar(grep { defined $_ } @weak_elements), 2,
    'dropping the root releases the non-escaped subtree');
$body = undef;
is(scalar(grep { defined $_ } @weak_elements), 0,
    'dropping the escaped subtree releases its remaining elements');

done_testing;
