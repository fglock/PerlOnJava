package Test::Moose;

# PerlOnJava Test::Moose shim.
#
# Covers the four exported helpers: meta_ok, does_ok, has_attribute_ok,
# with_immutable. Implemented on top of the Moose shim's _FakeMeta and
# Moose::Util::find_meta / does_role.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Test::Builder;
use Moose::Util qw(find_meta does_role);
use Class::MOP ();

use Exporter 'import';

our @EXPORT = qw(
    meta_ok
    does_ok
    has_attribute_ok
    with_immutable
);

our @EXPORT_OK = @EXPORT;

my $Test = Test::Builder->new;

sub meta_ok ($;$) {
    my ($class_or_obj, $message) = @_;
    $message ||= "The object has a meta";
    return $Test->ok( find_meta($class_or_obj) ? 1 : 0, $message );
}

sub does_ok ($$;$) {
    my ($class_or_obj, $does, $message) = @_;
    $message ||= "The object does $does";
    return $Test->ok( does_role($class_or_obj, $does) ? 1 : 0, $message );
}

sub has_attribute_ok ($$;$) {
    my ($class_or_obj, $attr_name, $message) = @_;
    $message ||= "The object does has an attribute named $attr_name";

    my $meta = find_meta($class_or_obj);
    if ($meta && $meta->can('find_attribute_by_name')) {
        return $Test->ok( $meta->find_attribute_by_name($attr_name) ? 1 : 0,
                          $message );
    }

    # Fall back to ->can($attr_name) on the class — under the shim, every
    # `has` becomes an accessor sub of that name.
    my $class = ref($class_or_obj) || $class_or_obj;
    return $Test->ok( $class && $class->can($attr_name) ? 1 : 0, $message );
}

sub with_immutable (&@) {
    my $block = shift;

    my $before = $Test->current_test;
    $block->(0);

    # Upstream calls `Class::MOP::class_of($_)->make_immutable` here.
    # Under the shim that's a no-op, but call ->meta->make_immutable
    # on classes that have one so the test still exercises the user
    # code path.
    for my $class (@_) {
        my $name = ref($class) || $class;
        next unless defined $name && length $name && !ref $name;
        next unless $name->can('meta');
        my $meta = eval { $name->meta };
        next unless $meta && $meta->can('make_immutable');
        eval { $meta->make_immutable };
    }
    $block->(1);

    my $num_tests = $Test->current_test - $before;
    my @results = ($Test->summary)[ -$num_tests .. -1 ];
    for my $r (@results) { return 0 unless $r; }
    return 1;
}

1;

__END__

=head1 NAME

Test::Moose - PerlOnJava shim for L<Test::Moose>.

=head1 DESCRIPTION

Implements C<meta_ok>, C<does_ok>, C<has_attribute_ok>, and
C<with_immutable> on top of the Moose-as-Moo shim. C<has_attribute_ok>
falls back to a C<< $class->can($attr_name) >> probe when no real
metaclass is available.

=cut
