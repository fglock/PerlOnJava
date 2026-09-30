use strict;
use warnings;
use Test::More;
use builtin qw(weaken);

sub refcount_is {
    # Do not unpack the first argument: qr// refcount observation depends on
    # the aliasing form used by Perl's core test helper.
    my (undef, $expected, $description) = @_;
    is(&Internals::SvREFCNT($_[0]) + 1, $expected, $description);
}

sub check_wrapper_lifetime {
    my $regex = qr/abcdef/;
    my $strong_copy = $regex;
    my $weak_copy = $regex;
    weaken($weak_copy);
    my $interpolated_copy = qr/$regex/;
    my $stringified = "$regex";

    is("$weak_copy", $stringified, 'weak copy preserves the source pattern');
    is("$strong_copy", $stringified, 'strong copy preserves the source pattern');
    is("$interpolated_copy", $stringified, 'interpolated wrapper preserves the pattern');

    my $before = Internals::SvREFCNT($$weak_copy);
    undef $regex;
    refcount_is($weak_copy, $before - 1,
        'removing the source reference decrements its wrapper count');

    undef $interpolated_copy;
    refcount_is($weak_copy, $before - 1,
        'removing the interpolated wrapper leaves the source wrapper count unchanged');
    is("$weak_copy", $stringified, 'weak copy remains valid through the strong copy');
}

check_wrapper_lifetime();
check_wrapper_lifetime();

done_testing();
