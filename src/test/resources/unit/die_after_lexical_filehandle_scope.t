use strict;
use warnings;
use Test::More tests => 2;

sub read_source_in_lexical_scope {
    open my $handle, '<', __FILE__ or die "open test source: $!";
    my @lines = <$handle>;
    return scalar @lines;
}

cmp_ok(read_source_in_lexical_scope(), '>', 0, 'read source through lexical filehandle');

sub render_template_exception {
#line 5 "template"
    eval { die 'oops!' };
    return $@;
}

is(
    render_template_exception(),
    "oops! at template line 5.\n",
    'die omits context from a filehandle closed at lexical scope exit',
);
