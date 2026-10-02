use strict;
use warnings;
use Errno qw(ENOENT);
use Test::More tests => 3;

my $missing = "rmdir-missing-$$";
ok(!-e $missing, 'test path is absent');

{
    local $!;
    ok(!rmdir($missing), 'rmdir fails for a nonexistent path');
    is(0 + $!, ENOENT, 'rmdir reports ENOENT');
}
