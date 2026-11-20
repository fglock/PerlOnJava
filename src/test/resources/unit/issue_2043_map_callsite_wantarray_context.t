use strict;
use warnings;
use Test::More;

sub context_name {
    my $context = wantarray;
    return !defined($context) ? 'void' : $context ? 'list' : 'scalar';
}

sub nested_scalar_context {
    return scalar context_name();
}

my @direct_list = map { context_name() } 1;
my @direct_scalar = map { scalar context_name() } 1;
my @nested_scalar = map { nested_scalar_context() } 1;

is_deeply(\@direct_list, ['list'], 'direct map callback call receives list context');
is_deeply(\@direct_scalar, ['scalar'], 'explicit scalar call inside map stays scalar');
is_deeply(\@nested_scalar, ['scalar'], 'nested scalar call inside map stays scalar');

done_testing();
