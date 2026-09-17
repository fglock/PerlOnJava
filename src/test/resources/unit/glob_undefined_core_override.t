use strict;
use warnings;
use Test::More;
use File::Glob ();

no warnings 'redefine';
my $first_calls = 0;
my $second_calls = 0;
*File::Glob::csh_glob = sub { ++$first_calls };
my $first = eval q{ glob(q(./"TEST")) };

undef *CORE::GLOBAL::glob;
++ $CORE::GLOBAL::glob if 0;
undef *File::Glob::csh_glob;
*File::Glob::csh_glob = sub { ++$second_calls };
my $second = eval q{ glob(q(./"TEST")) };

is $first_calls, $second_calls,
    'glob uses File::Glob::csh_glob before and after an undefined CORE override';
is $first, $second,
    'an undefined CORE::GLOBAL::glob slot does not change the fallback result';

done_testing;
