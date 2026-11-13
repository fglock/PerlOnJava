use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 4;

our @values;
our @DESTROY_OBSERVED;
our @AFTER_CLEAR;

{
    package DetachedGlobArrayAliasClearOrder::Probe;

    sub DESTROY {
        push @main::DESTROY_OBSERVED,
            defined($_[0]) ? 'alias-defined' : 'alias-undef',
            defined($main::values[0]) ? 'array-defined' : 'array-undef';
    }
}

my $object = bless {}, 'DetachedGlobArrayAliasClearOrder::Probe';
my $weak = $object;
weaken($weak);
{
    no strict 'refs';
    ${'DetachedGlobArrayAliasClearOrder::value'} = $object;
}
undef $object;

my $glob = delete $DetachedGlobArrayAliasClearOrder::{value};
@values = ($glob);
undef $glob;
ok(defined $weak, 'the array-held detached glob keeps its scalar value alive');

sub clear_values {
    undef @values;
    push @AFTER_CLEAR, defined($_[0]) ? 'defined' : 'undef';
}

clear_values($values[0]);

is_deeply(
    \@DESTROY_OBSERVED,
    ['alias-defined', 'array-defined'],
    'component DESTROY runs before array and argument aliases are cleared',
);
is_deeply(\@AFTER_CLEAR, ['undef'], 'the active argument alias is cleared afterward');
is($weak, undef, 'array clear releases the detached component value');

done_testing;
