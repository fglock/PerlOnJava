use strict;
use warnings;
use Test::More tests => 2;

sub first { $_[0] }

my $same = first(<<END, 'unused') eq
hello
END
q(hello
);

ok($same, 'quote-like RHS resumes after a heredoc body');
is(first(<<END, 'unused'), "world\n", 'heredoc remains the first call argument');
world
END
