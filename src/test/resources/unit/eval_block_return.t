use strict;
use warnings;
use Test::More tests => 4;

sub eval_return_list {
    my @values = eval { return (1, 2, 3) };
    return "after:" . join("|", @values);
}

sub eval_return_scalar {
    my $value = eval { return (1, 2, 3) };
    return "after:$value";
}

sub eval_return_date_parts {
    my @values = eval {
        my @parts = (12, 11, 9, 2, 0, 124);
        return @parts;
    };
    $values[5] += 1900;
    return join("|", @values);
}

is(eval_return_list(), 'after:1|2|3', 'return inside eval block supplies list eval value');
is(eval_return_scalar(), 'after:3', 'return inside eval block supplies scalar eval value');
is(eval_return_date_parts(), '12|11|9|2|0|2024', 'caller continues after eval block return');

my $at = 'unchanged';
eval { return 'ok' };
is($@, '', 'successful eval block return clears eval error');
