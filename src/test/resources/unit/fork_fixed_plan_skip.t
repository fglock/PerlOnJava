use strict;
use warnings;
use Config;
use Test::More;

if ($Config{d_fork}) {
    plan skip_all => 'requires a platform without process fork support';
}

plan tests => 3;
pass 'portable assertions before fork still run';

fork();
fail 'code after unsupported fork is not run';
fail 'fixed plan is completed with skips';
