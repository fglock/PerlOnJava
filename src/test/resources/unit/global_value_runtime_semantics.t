use strict;
use warnings;
use Test::More tests => 12;

$Phase8a::scalar = 'outer';
@Phase8a::array = qw(outer array);
%Phase8a::hash = (key => 'outer');

is($Phase8a::scalar, 'outer', 'package scalar stores a value');
is_deeply(\@Phase8a::array, [qw(outer array)], 'package array stores values');
is_deeply(\%Phase8a::hash, {key => 'outer'}, 'package hash stores values');

*Phase8a::scalar_alias = *Phase8a::scalar;
$Phase8a::scalar_alias = 'aliased';
is($Phase8a::scalar, 'aliased', 'scalar glob alias shares its value slot');

{
    local $Phase8a::scalar = 'local';
    local @Phase8a::array = qw(local array);
    local %Phase8a::hash = (key => 'local');
    is($Phase8a::scalar, 'local', 'localized scalar uses temporary slot');
    is_deeply(\@Phase8a::array, [qw(local array)], 'localized array uses temporary slot');
    is_deeply(\%Phase8a::hash, {key => 'local'}, 'localized hash uses temporary slot');
    is($Phase8a::scalar_alias, 'local', 'scalar alias observes localization');
}

is($Phase8a::scalar, 'aliased', 'localized scalar restores original slot');
is_deeply(\@Phase8a::array, [qw(outer array)], 'localized array restores original slot');
is_deeply(\%Phase8a::hash, {key => 'outer'}, 'localized hash restores original slot');
is($Phase8a::scalar_alias, 'aliased', 'scalar alias observes restored slot');
