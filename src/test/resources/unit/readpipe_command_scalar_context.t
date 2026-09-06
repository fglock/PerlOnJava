use strict;
use warnings;
use Test::More;

sub command_for_context {
    my $context = wantarray ? 'list' : defined wantarray ? 'scalar' : 'void';
    qq{$^X -le "print q($context)"};
}

is scalar readpipe(command_for_context()), "scalar\n",
    'readpipe command is evaluated in scalar context';
is join(',', 'before', readpipe(command_for_context()), 'after'), "before,scalar\n,after",
    'readpipe command stays scalar in list result context';

done_testing;
