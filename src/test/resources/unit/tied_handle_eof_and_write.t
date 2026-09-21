use strict;
use warnings;
use Test::More tests => 5;
use Symbol qw(gensym);

{
    package LocalTie;
    sub TIEHANDLE { bless { calls => [] }, shift }
    sub READLINE { "line\n" }
    sub EOF { push @{ $_[0]{calls} }, $_[1]; 1 }
    sub WRITE { push @{ $_[0]{calls} }, [ WRITE => scalar @_ ]; 1 }
    sub PRINT { 1 }
}

my $fh = gensym;
my $tie = tie *$fh, 'LocalTie';
is(eof($fh), 1, 'explicit tied eof uses EOF flag 1');
ok(defined(eof()), 'parenthesized tied eof dispatches');
@{$tie->{calls}} = ();
my $line = <$fh>;
is(eof, 1, 'bare tied eof uses EOF flag 0');
is_deeply($tie->{calls}, [0], 'bare tied eof passes zero flag');
@{$tie->{calls}} = ();
syswrite($fh, 'abc', 3);
is_deeply($tie->{calls}, [[WRITE => 3]], 'tied WRITE receives data and length only');
