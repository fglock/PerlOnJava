use strict;
use warnings;
use B qw(svref_2object);
use Test::More;

our $destroyed = 0;
our $wrapper;

{
    package Issue2043::BWrapperTarget;
    sub DESTROY { $main::destroyed++ }
}

{
    my $target = bless {}, 'Issue2043::BWrapperTarget';
    $wrapper = B::svref_2object($target);
}

is($destroyed, 1,
    'a retained B::SV wrapper does not keep its Perl referent alive');
ok($wrapper, 'the non-owning B::SV wrapper itself remains available');

done_testing;
