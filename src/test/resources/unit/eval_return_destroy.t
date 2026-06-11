use strict;
use warnings;
use Test::More;

subtest 'explicit return from eval-created sub releases returned lexical owner' => sub {
    @EvalReturnDestroy::log = ();
    {
        package EvalReturnDestroy;
        our @log;
        sub DESTROY { push @log, 'destroyed' }
    }

    my $ctor = eval q{
        sub {
            my $x = bless {}, 'EvalReturnDestroy';
            return $x;
        }
    };
    die $@ if $@;

    { $ctor->(); }
    is_deeply(\@EvalReturnDestroy::log, ['destroyed'],
        'discarded return value is destroyed after the caller statement');
};

done_testing();
