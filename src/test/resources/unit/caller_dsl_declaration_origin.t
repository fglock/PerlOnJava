use strict;
use warnings;
use Test::More;
use Carp ();

{
    package CallerMetadata::Metadata;
    our @CARP_NOT = __PACKAGE__;

    sub add { Carp::shortmess('declared') }
    sub make_resource {
        my $metadata = shift;
        return sub { $metadata->add(@_) };
    }
}

our $metadata;
BEGIN {
    $metadata = bless {}, 'CallerMetadata::Metadata';
    *resource = CallerMetadata::Metadata::make_resource($metadata);
}

my $origin = resource config =>
    dependencies => [qw(first second)],
    require => 'Example::Dependency',
    init => sub {};
my $expected_line = __LINE__ - 1;
like($origin, qr/line $expected_line\b/,
    'anonymous imported DSL declaration origin points to its final initializer line');

done_testing;
