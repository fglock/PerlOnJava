use strict;
use warnings;
no warnings 'once';
use utf8;

my $glob = \*StashUndefProbe::glòb;
sub StashUndefDestroy::DESTROY { eval '++$StashUndefProbe::value' }
{
    no strict 'refs';
    ${'StashUndefProbe::object'} = bless [], 'StashUndefDestroy';
}
undef %StashUndefProbe::;

print "1..1\n";
print(($$glob eq '*__ANON__::glòb' ? 'ok' : 'not ok'),
    " 1 - saved glob stringifies through the anonymized stash\n");
