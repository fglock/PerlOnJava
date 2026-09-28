use v5.36;
use feature qw(state declared_refs);
no warnings 'experimental::declared_refs';
use Scalar::Util qw(refaddr);
use Test::More;

is eval q{
    my @ret = state \($b, $c);
    Scalar::Util::refaddr($ret[0]) == Scalar::Util::refaddr(\$b)
        && Scalar::Util::refaddr($ret[1]) == Scalar::Util::refaddr(\$c);
}, 1, 'state reference-list declaration returns both bound scalar cells';

is eval q{
    my @ret = state \(@b, @c);
    Scalar::Util::refaddr($ret[0]) == Scalar::Util::refaddr(\@b)
        && Scalar::Util::refaddr($ret[1]) == Scalar::Util::refaddr(\@c);
}, 1, 'state reference-list declaration returns both bound array cells';

is eval q{
    my @ret = state \(%b, %c);
    Scalar::Util::refaddr($ret[0]) == Scalar::Util::refaddr(\%b)
        && Scalar::Util::refaddr($ret[1]) == Scalar::Util::refaddr(\%c);
}, 1, 'state reference-list declaration returns both bound hash cells';

done_testing;
