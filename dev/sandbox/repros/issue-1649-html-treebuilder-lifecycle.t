use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @OBJECTS;
our $in_core_bless;

BEGIN {
    no strict 'refs';
    *{'CORE::GLOBAL::bless'} = sub {
        my $reference = shift;
        my $class = @_ ? shift : scalar caller;
        my $object = CORE::bless($reference, $class);
        if ($object->isa('HTML::Element') && !$main::in_core_bless) {
            local $main::in_core_bless = 1;
            push @main::OBJECTS, $object;
            Scalar::Util::weaken($main::OBJECTS[-1]);
        }
        return $object;
    };
}

my $have_html_tree = eval { require HTML::TreeBuilder; 1 };
plan skip_all => 'HTML::Tree is not installed' unless $have_html_tree;

sub object_count { scalar grep { defined $_ } @OBJECTS }
sub clear_objects { @OBJECTS = () }

{
    my $tree = HTML::TreeBuilder->new_from_content('&amp;foo; &bar;');
    ok(object_count() > 0, 'content parsing creates elements');
    $tree = undef;
    is(object_count(), 0, 'dropping a content-parsed tree releases its elements');
    clear_objects();
}

{
    my $tree = HTML::TreeBuilder->new(no_expand_entities => 1);
    $tree->parse("<p>&amp;foo; &bar; &#39; &l</p>");
    ok(object_count() > 0, 'incremental parsing creates elements');
    $tree = undef;
    is(object_count(), 0, 'dropping an incrementally parsed tree releases its elements');
    clear_objects();
}

done_testing;
