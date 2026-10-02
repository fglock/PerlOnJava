use strict;
use warnings;
use Test::More;

my $handle_name = 'filenoNoVivification';

ok(!defined fileno($handle_name), 'fileno on an unopened symbolic handle is undef');
ok(!defined *{$handle_name}, 'fileno does not create the symbolic typeglob');
{
    no warnings 'unopened';
    ok(!close $handle_name, 'close on an unopened symbolic handle is false');
}
ok(!defined *{$handle_name}, 'close does not create the symbolic typeglob');

$handle_name++;
ok(!defined fileno($handle_name), 'fileno on the next unopened symbolic handle is undef');
ok(!defined *{$handle_name}, 'fileno does not create the incremented typeglob');
{
    no warnings 'unopened';
    ok(!close $handle_name, 'close on the incremented unopened handle is false');
}
ok(!defined *{$handle_name}, 'close does not create the incremented typeglob');

done_testing();
