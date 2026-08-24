use strict;
use warnings;
use Test::More;

# Executable empty-pattern recursion crosses many nested regex callback frames.
# Every exceptional unwind must retain Perl's public diagnostic; an internal
# frame-lifecycle assertion must never replace it, including after JIT warmup.
for my $iteration (1 .. 24) {
    local $_ = 'ab';
    my $pattern = qr/(?{ s!!x! })/;
    my $ok = eval {
        /$pattern/;
        /a/;
        /$pattern/;
        /b/;
        /$pattern/;
        //;
        1;
    };

    ok(!$ok, "iteration $iteration dies");
    like($@, qr/^Infinite recursion via empty pattern/,
         "iteration $iteration preserves the Perl diagnostic");
}

done_testing;
