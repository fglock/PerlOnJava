use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

our %CACHE;

sub compare_expected {
    my ($expected) = @_;
    local %CACHE;
    $CACHE{wrapper} = bless { value => $expected }, 'Local::Wrapper';
    return 1;
}

sub capture_owner {
    my ($value) = @_;
    return sub { $value };
}

sub fresh_scalar_ref {
    my $value = 42;
    return \$value;
}

{
    my $value = [];
    my $weak = $value;
    weaken($weak);

    compare_expected($value);
    $value = 1;

    ok(!defined($weak), 'localized cache releases a wrapped argument at scope exit');
}

{
    my $value = [];
    my $weak = $value;
    weaken($weak);
    my $metadata = [$value];

    $value = 1;
    ok(defined($weak), 'nested lexical metadata remains a strong owner');

    $metadata = undef;
    ok(!defined($weak), 'weak reference clears when nested metadata is released');
}

{
    my $value = [];
    my $weak = $value;
    weaken($weak);
    my $callback = capture_owner($value);

    $value = 1;
    ok(defined($weak), 'closure capture remains a strong owner');

    $callback = undef;
    ok(!defined($weak), 'weak reference clears when closure owner is released');
}

{
    my $strong = fresh_scalar_ref();
    my $weak = $strong;
    weaken($weak);
    my $callback = capture_owner($strong);

    $strong = undef;
    ok(defined($weak), 'closure capture keeps a scalar referent alive');

    $callback = undef;
    ok(!defined($weak), 'scalar weak reference clears with its closure owner');
}

done_testing;
