use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

our %source = (answer => 42);

{
    package LvrefGlobalHashAlias;

    our %target;

    sub scalar_hash_reference {
        return wantarray ? () : \%main::source;
    }

    \%target = scalar_hash_reference();
    Test::More::is($target{answer}, 42,
        'package hash aliases a reference returned in scalar context');
    $target{answer} = 43;
}

is($source{answer}, 43,
    'writes through the package hash alias update the referent');

{
    package LvrefParenthesizedHashAlias;

    our %source = (answer => 42);
    our %target;
    my ($placeholder, $source_placeholder);
    \($placeholder, %target) = (\$source_placeholder, \%source);
    Test::More::is($target{answer}, 42,
        'parenthesized package hash alias installs the source hash');
    $target{answer} = 43;
}

is($LvrefParenthesizedHashAlias::source{answer}, 43,
    'writes through the parenthesized hash alias update the referent');

{
    package LvrefGlobalCodeAlias;

    sub source_sub { 'source' }
    \(&target_sub) = \&source_sub;
    Test::More::is(target_sub(), 'source',
        'parenthesized package code alias installs the source subroutine');

}

done_testing;
