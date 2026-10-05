use strict;
use warnings;
use Test::More;

{
    package Local::NamedPipeMethods;
    use strict;
    use warnings;
    use IO::Handle;

    sub exercise_named_pipe_methods {
        pipe(READ_FH, WRITE_FH) or die "pipe: $!";
        READ_FH->autoflush(1);
        WRITE_FH->autoflush(1);
        print WRITE_FH "ok\n" or die "write pipe: $!";
        my $count = read(READ_FH, my $buffer, 3);
        close READ_FH;
        close WRITE_FH;

        main::is($count, 3, 'bare named pipe methods resolve in their package');
        main::is($buffer, "ok\n", 'named pipe methods retain the connected handles');
    }

    exercise_named_pipe_methods();
}

done_testing;
