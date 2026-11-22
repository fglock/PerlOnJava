use strict;
use warnings;
use feature 'try';
use Test::More;

BEGIN {
    require './src/main/perl/lib/Syntax/Keyword/Try.pm';
    $INC{'Syntax/Keyword/Try.pm'} = 'src/main/perl/lib/Syntax/Keyword/Try.pm';
}
use Syntax::Keyword::Try;

my @events;
sub succeeds {
    try {
        push @events, 'try';
        return 'ok';
    }
    catch ($error) {
        return "caught: $error";
    }
}

sub fails {
    try {
        die "problem\n";
    }
    catch ($error) {
        push @events, "catch:$error";
    }
}

is(succeeds(), 'ok', 'return inside try returns from the containing sub');
is_deeply(\@events, ['try'], 'try body runs once on normal completion');
fails();
is_deeply(\@events, ['try', "catch:problem\n"],
    'catch receives the exception');

my $propagated = eval {
    try { die "uncaught\n" }
    catch ($error) { die $error }
    1;
};
ok(!$propagated, 'an exception thrown in catch propagates');
like($@, qr/uncaught/, 'the propagated exception is preserved');

done_testing();
