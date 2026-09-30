use strict;
use warnings;
use Test::More;

my $name = 'POJFH' . $$ . '00';

ok(!defined *{$name}, 'undefined symbolic filehandle has no visible glob');
ok(!defined fileno($name), 'fileno of unopened symbolic filehandle is undef');
ok(!defined *{$name}, 'fileno probe does not make the glob visible');

my $previous = select($name);
ok(defined *{$name}, 'select creates the named glob');
select($previous);

$name++;
my $closed;
{
    local $SIG{__WARN__} = sub {};
    $closed = close $name;
}
ok(!$closed, 'close on incremented, unopened filehandle fails');
ok(!defined *{$name}, 'failed close does not make the new glob visible');

done_testing;
