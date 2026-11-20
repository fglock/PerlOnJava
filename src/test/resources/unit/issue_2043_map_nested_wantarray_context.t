use strict;
use warnings;
use Test::More;

my ($direct_map_context, $nested_scalar_context);

sub context_name {
    my $context = wantarray;
    return !defined($context) ? 'void' : $context ? 'list' : 'scalar';
}

sub record_direct_map_context {
    my $context = wantarray;
    $direct_map_context = !defined($context) ? 'void' : $context ? 'list' : 'scalar';
}

sub record_nested_scalar_context {
    if (my $context = context_name()) {
        $nested_scalar_context = $context;
    }
}

map { record_direct_map_context() } 1;
map { record_nested_scalar_context() } 1;

is($direct_map_context, 'list', 'sub called directly from map receives list context');
is($nested_scalar_context, 'scalar', 'scalar subcall keeps its context inside a map-called sub');

done_testing();
