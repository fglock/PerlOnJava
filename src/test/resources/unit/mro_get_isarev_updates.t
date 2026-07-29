use strict;
use warnings;
use mro;

my $test = 0;
sub check {
    my ($pass, $name) = @_;
    ++$test;
    print(($pass ? 'ok' : 'not ok'), " $test - $name\n");
}

sub check_array {
    my ($got, $expected, $name) = @_;
    check(
        @$got == @$expected
            && join("\0", @$got) eq join("\0", @$expected),
        $name,
    );
}

print "1..6\n";

{
    package IsarevBase;
    our $VERSION = 1;
}

check_array(
    mro::get_isarev('IsarevBase'),
    [],
    'initial reverse inheritance lookup is empty',
);

eval q{
    package IsarevChild;
    use base qw(IsarevBase);
    1;
} or die $@;

check_array(
    mro::get_isarev('IsarevBase'),
    ['IsarevChild'],
    'reverse inheritance lookup sees a class added after the cache was read',
);

eval q{
    package IsarevGrandchild;
    use parent -norequire, qw(IsarevChild);
    1;
} or die $@;

check_array(
    [sort @{mro::get_isarev('IsarevBase')}],
    [qw(IsarevChild IsarevGrandchild)],
    'reverse inheritance lookup includes newly added indirect subclasses',
);

{
    package IsarevUniversalParent;
    sub marker { 1 }

    package IsarevLinearChild;
    our @ISA = ('IsarevBase');
}

{
    local @UNIVERSAL::ISA = ('IsarevUniversalParent');

    check_array(
        mro::get_linear_isa('IsarevLinearChild'),
        [qw(IsarevLinearChild IsarevBase)],
        'implicit UNIVERSAL parents are hidden from another class linearization',
    );
    check_array(
        mro::get_linear_isa('UNIVERSAL'),
        [qw(UNIVERSAL IsarevUniversalParent)],
        'UNIVERSAL exposes its own explicit parents',
    );
    check(
        IsarevLinearChild->can('marker'),
        'UNIVERSAL parent remains available to method lookup',
    );
}
