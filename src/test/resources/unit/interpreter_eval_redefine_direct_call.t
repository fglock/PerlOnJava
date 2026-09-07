use strict;
use warnings;
use Test::More;

sub lazy {
    eval q{ *lazy = sub { 42 } };
    die $@ if $@;
    &lazy;
}

is lazy(), 42, 'an explicit named call observes an eval-installed replacement';
done_testing;
