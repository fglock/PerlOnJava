use strict;
use warnings FATAL => 'all';
no warnings qw(uninitialized regexp deprecated);
use threads;
use Test::More tests => 6;

for my $case (
    [q{\c`}, qr/is more clearly written simply as/],
    [q{\c1}, qr/is more clearly written simply as/],
    [q{\E},  qr/Useless use of \\E/],
) {
    my ($pattern, $expected) = @$case;
    eval "qr/$pattern/";
    my $direct_error = $@;
    eval "threads->new(sub { qr/$pattern/ })->join()";
    my $thread_error = $@;

    like($direct_error, $expected, "direct qr/$pattern/ reports its fatal compile warning");
    like($thread_error, $expected,
        "thread-entry qr/$pattern/ reports the same fatal warning in the parent eval");
}
