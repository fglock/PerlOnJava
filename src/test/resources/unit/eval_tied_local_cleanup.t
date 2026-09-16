use strict;
use warnings;
use Test::More;

{
    package EvalTiedLocalCleanup;

    sub TIEHASH { bless {}, $_[0] }
    sub EXISTS  { 0 }
    sub FETCH   { undef }
    sub STORE   { }
    sub DELETE  { die "outer\n" }

    my $destroyed = 0;
    sub DESTROY { $destroyed = 1 }

    sub invoke_with_local_tie {
        my $capture;
        my $callback = sub {
            $capture = 1;
            my %tied;
            tie %tied, __PACKAGE__;
            local $tied{key} = 'value';
            die "inner";
        };
        bless $callback, __PACKAGE__;
        $callback->();
    }

    eval { invoke_with_local_tie() };
    ::is($@, "outer\n", 'eval retains the tied-local cleanup exception');
    ::is($destroyed, 1, 'callback is destroyed while eval unwinds');
}

done_testing;
