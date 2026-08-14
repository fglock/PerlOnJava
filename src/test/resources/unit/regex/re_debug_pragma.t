use strict;
use warnings;
use Test::More tests => 6;

{
    use re 'debug';
    ok('alpha' =~ /alpha/, "use re 'debug' preserves matching");

    {
        no re 'debug';
        ok('beta' =~ /beta/, "no re 'debug' is accepted in a nested scope");
    }

    ok('gamma' =~ /gamma/, 'debug hint is restored after the nested scope');
}

{
    use re 'debugcolor';
    ok('delta' =~ /delta/, "use re 'debugcolor' preserves matching");
}

ok('epsilon' =~ /epsilon/, 'debug hint does not leak out of its lexical scope');

use threads;
my $thread = threads->create(sub {
    use re 'debug';
    return 'thread-owned' =~ /thread-owned/;
});
ok($thread->join(), 'a child runtime executes a regex compiled with lexical debug');
