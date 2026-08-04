use strict;
use warnings;

use Test::More tests => 6;

sub expand_order_like {
    my ($self, $arg) = @_;
    return unless defined($arg)
        and not(ref($arg) eq 'ARRAY' and !@$arg);

    my $expander = sub {
        my ($invocant, $direction, $expression) = @_;
        return { expression => $expression };
    };

    local @{$self->{expand}}{qw(asc desc new)} = (($expander) x 3);
    my $result = $self->$expander(undef, $arg);
    return [
        $result,
        ref($self->{expand}{asc}),
        ref($self->{expand}{desc}),
        ref($self->{expand}{new}),
    ];
}

my $object = { expand => { asc => 'old asc', desc => 'old desc' } };
my $result = expand_order_like($object, [42]);
is_deeply($result->[0], { expression => [42] },
    'coderef method call after localized hash slice returns its result');
is_deeply([ @{$result}[1 .. 3] ], [ qw(CODE CODE CODE) ],
    'localized hash slice entries receive the coderefs inside the scope');
is($object->{expand}{asc}, 'old asc', 'localized asc entry is restored');
is($object->{expand}{desc}, 'old desc', 'localized desc entry is restored');
ok(!exists $object->{expand}{new}, 'new localized entry is removed after the scope');
is_deeply([sort keys %{$object->{expand}}], [qw(asc desc)],
    'localized slice preserves the original hash keys');
