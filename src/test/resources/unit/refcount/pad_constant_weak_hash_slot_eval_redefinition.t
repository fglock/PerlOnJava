use strict;
use warnings;
use Test::More;
use Scalar::Util ();

{
    package Issue2043::ReadonlyWeakSlot;
    use Scalar::Util ();

    # This mirrors the relevant part of Moo's generated constructor: build a
    # temporary argument hash, copy the value into a fresh object hash, then
    # weaken the assignment expression itself.
    sub new {
        my $class = shift;
        my $args = scalar @_ == 1
            ? ref $_[0] eq 'HASH' ? { %{ $_[0] } } : die "bad arguments"
            : @_ % 2 ? die "odd arguments" : {@_};
        my $object = bless {}, $class;
        exists $args->{value} && do {
            &Scalar::Util::weaken(($object->{value} = $args->{value}));
            no warnings 'void';
            $object->{value};
        };
        return $object;
    }
}

sub issue2043_static_ref { \ 'readonly value' }

my $object = eval {
    Issue2043::ReadonlyWeakSlot->new(value => issue2043_static_ref());
};

if ("$]" < 5.008_003) {
    like(
        $@,
        qr/Reference to readonly value in "value" can not be weakened on Perl < 5.8.3/,
        'old Perl rejects weakening a readonly value',
    );
}
elsif ($^O eq 'cygwin' and "$]" < 5.012_000) {
    SKIP: { skip 'Static coderef reaping seems nonfunctional on cygwin < 5.12', 1 }
}
else {
    is($@, '', 'constructor accepts the readonly reference inside block eval');
    is(${$object->{value}}, 'readonly value', 'weak field remains live while producer CV is installed');
    ok(Scalar::Util::isweak($object->{value}), 'the object field is weakened');

    {
        no warnings 'redefine';
        *issue2043_static_ref = sub {};
    }
    ok(!defined($object->{value}), 'redefining the producer reaps its optree constant');
}

done_testing;
