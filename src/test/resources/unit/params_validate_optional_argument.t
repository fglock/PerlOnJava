use strict;
use warnings;
use Test::More;

sub validate_named_arguments {
    my %input = @_;
    my %specification = (
        required => { optional => 0 },
        optional => { optional => 1 },
    );
    my %arguments;

    OUTER: for my $name (qw(required optional)) {
        my $value = do {
            if (exists $specification{$name}{default}) {
                $specification{$name}{default};
            }
        } || do {
            next OUTER if $specification{$name}{optional} && !exists $input{$name};
            $input{$name};
        };

        $arguments{$name} = $value;
    }

    return exists $arguments{optional} ? 'explicit-undef' : 'omitted';
}

sub false_if_result {
    if ($_[0]) { 'taken' }
}

is(
    false_if_result(0),
    0,
    'an untaken if without else returns its false condition value',
);

is(
    false_if_result(1),
    'taken',
    'a taken if without else returns its branch value',
);

is(
    validate_named_arguments(required => 'value'),
    'omitted',
    'omitted optional named arguments are not materialized as undef',
);

is(
    validate_named_arguments(required => 'value', optional => undef),
    'explicit-undef',
    'explicit undef remains distinguishable from an omitted argument',
);

done_testing;
