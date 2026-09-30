use strict;
use warnings;
use Test::More tests => 6;
my @packages;
my @frames;
sub named_hook {
    push @packages, scalar caller(1);
    @frames = caller(0);
}
{
    local $SIG{__DIE__} = sub { push @packages, scalar caller(1) };
    eval '/x';
}
is($packages[0], 'main', 'syntax-error die hook retains eval caller frame');
like($@, qr/Search pattern not terminated/, 'syntax error is retained by eval');
{
    local $SIG{__DIE__} = \&named_hook;
    eval '/x';
}
is($packages[1], 'main', 'named syntax-error die hook retains eval caller frame');
is($frames[3], 'main::named_hook', 'syntax-error hook retains its own subroutine name');
like($@, qr/Search pattern not terminated/, 'named hook does not lose the syntax error');
my $success = eval '42';
is($success, 42, 'subsequent successful eval is unaffected by hook cleanup');
