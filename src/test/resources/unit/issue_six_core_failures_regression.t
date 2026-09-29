use strict;
use warnings;
no warnings 'portable';
use Test::More;
use mro;

{
    package ResetIsaRegression;
    our @ISA = qw(ResetParentA ResetParentB);
    reset 'I';
}
is_deeply(mro::get_linear_isa('ResetIsaRegression'), ['ResetIsaRegression'],
    'reset clears @ISA in the active package');

my @recursive_isa = (
    '@CycleOne::ISA = "CycleTwo"; @CycleTwo::ISA = "CycleOne";',
    '@CycleThree::ISA = "CycleFour"; push @CycleFour::ISA, "CycleThree";',
    '@CycleFive::ISA = "CycleSix"; @CycleSix::ISA = qw(Parent CycleFive Other);',
    '@CycleSeven::ISA = "CycleEight"; push @CycleEight::ISA, qw(Parent CycleSeven Other);',
);
for my $code (@recursive_isa) {
    eval $code;
    like($@, qr/Recursive inheritance detected/, 'cyclic @ISA is rejected');
}

@Goat::ISA = ('Ungulate');
@Goat::Dairy::ISA = ('Goat');
@Goat::Dairy::Toggenburg::ISA = ('Goat::Dairy');
@Weird::Thing::ISA = ('g');
*g:: = *Goat::;
is_deeply([sort @{mro::get_isarev('Goat')}],
    [qw(Goat::Dairy Goat::Dairy::Toggenburg Weird::Thing)],
    'reverse inheritance resolves an aliased parent stash');
delete $::{'g::'};
is_deeply([sort @{mro::get_isarev('Ungulate')}],
    [qw(Goat Goat::Dairy Goat::Dairy::Toggenburg)],
    'removing a stash alias leaves reverse inheritance under the original stash');

@Caprine::ISA = ('Hoofed::Mammal');
@Caprine::Dairy::ISA = ('Caprine');
@Caprine::Dairy::Oberhasli::ISA = ('Caprine::Dairy');
@Whatever::ISA = ('Caprine');
*Caprid:: = *Caprine::;
*Caprine:: = *Chevre::;
is_deeply([sort @{mro::get_isarev('Chevre')}], [qw(Caprid::Dairy Whatever)],
    'rebound stashes retain reverse inheritance for moved nested packages');

undef *Empty::;
@Null::ISA = ('Empty');
@Null::Null::ISA = ('Empty::Empty');
{ package Zilch::Empty }
*Empty:: = *Zilch::;
is_deeply([sort @{mro::get_isarev('Zilch')}], ['Null'],
    'assigning an alias into an empty stash updates reverse inheritance');
is_deeply([sort @{mro::get_isarev('Zilch::Empty')}], ['Null::Null'],
    'assigning an alias into an empty stash includes nested packages');

my $uv_q = 0xFFFFFFFFFFFFFFFF / 3;
is("$uv_q", '6148914691236517205', 'exact UV quotient retains all integer bits');
isnt($uv_q, 0x5555555555555556, 'exact UV quotient compares without losing low bits');
is(0xFFFFFFFFFFFFFFFF % 0x5555555555555555, 0, 'UV modulus is exact');
is(0xFFFFFFFFFFFFFFFF % 0xFFFFFFFFFFFFFFF0, 15, 'UV modulus handles a large divisor');
is(0x8000000000000000 % 9223372036854775807, 1, 'modulus handles the signed IV boundary');
is(0x8000000000000000 % -9223372036854775807,
    -9223372036854775806, 'negative divisor gives a negative UV remainder');

my @subs;
push @subs, sub :const { $_ } for 1 .. 10;
is(join(' ', map &$_, @subs), '1 2 3 4 5 6 7 8 9 10',
    ':const captures each localized global topic value');

my $x = 3;
my $sub = sub :const { $x };
$x++;
is(&$sub, 3, ':const snapshots a captured lexical');
$x = 3;
$sub = sub :const { $x + 5 };
$x++;
is(&$sub, 8, ':const snapshots a captured lexical expression');

*constant_attr_regression = sub () :const { 42 };
{
    use warnings 'redefine';
    my $warning;
    local $SIG{__WARN__} = sub { $warning .= shift };
    *constant_attr_regression = sub () {};
    like($warning, qr/^Constant subroutine main::constant_attr_regression redefined at /,
        'redefining a constant sub emits the constant warning');
}

for my $source ('sub named_const_stub : const', 'sub named_const_body : const { }') {
    eval $source;
    like($@, qr/^:const is not permitted on named subroutines at /,
        ':const is rejected on named subs');
}

done_testing;
